CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenants: one row per clinic. tenant_id everywhere below refers to
-- clinics.id, not the Keycloak organization id directly - we keep our own
-- UUID as the tenant key so the app is not hard-wired to Keycloak's id
-- format (see TenantContextFilter).
CREATE TABLE clinics (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_org_id  VARCHAR(64) NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Local mirror of a Keycloak user, written once at provision time. tenant_id
-- here is NEVER consulted for authorization - the per-request token (via
-- TenantContextFilter) is the only source of truth, so moving a user between
-- Keycloak orgs takes effect immediately with no row to update here. Patient
-- tokens (not a member of any Organization) and platform_admin tokens have
-- tenant_id = NULL.
CREATE TABLE app_users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id  VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         UUID REFERENCES clinics(id),
    display_name      VARCHAR(255),
    email             VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_users_tenant ON app_users(tenant_id);

-- Per-clinic patient directory (per-clinic scope decision - see CLAUDE.md):
-- a patient seen at two different clinics gets a separate record at each,
-- with no cross-clinic linking.
CREATE TABLE patients (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES clinics(id),
    first_name            VARCHAR(120) NOT NULL,
    last_name             VARCHAR(120) NOT NULL,
    date_of_birth         DATE,
    phone                 VARCHAR(30),
    email                 VARCHAR(255),
    national_id           VARCHAR(60),
    insurance_member_id   VARCHAR(60),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_patients_tenant ON patients(tenant_id);

CREATE TABLE rooms (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rooms_tenant ON rooms(tenant_id);

CREATE TABLE providers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    app_user_id  UUID REFERENCES app_users(id), -- the provider's own login, if any
    full_name    VARCHAR(255) NOT NULL,
    specialty    VARCHAR(120),
    room_id      UUID REFERENCES rooms(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_providers_tenant ON providers(tenant_id);

-- A provider's recurring weekly availability - slots (below) are generated
-- from this plus an appointment type's duration.
CREATE TABLE provider_working_hours (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    provider_id  UUID NOT NULL REFERENCES providers(id),
    day_of_week  SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6), -- 0=Sunday
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL
);
CREATE INDEX idx_provider_working_hours_tenant ON provider_working_hours(tenant_id);
CREATE INDEX idx_provider_working_hours_provider ON provider_working_hours(provider_id);

CREATE TABLE appointment_types (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    name              VARCHAR(120) NOT NULL,
    duration_minutes  INT NOT NULL,
    price_amount      NUMERIC(10,2) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_appointment_types_tenant ON appointment_types(tenant_id);

CREATE TABLE slots (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES clinics(id),
    provider_id           UUID NOT NULL REFERENCES providers(id),
    appointment_type_id   UUID NOT NULL REFERENCES appointment_types(id),
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'open', -- open, booked
    -- Unused for pricing in v1, kept so a slot_class -> multiplier hook can
    -- be added later without a schema migration.
    slot_class            VARCHAR(20) NOT NULL DEFAULT 'standard',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_slots_tenant ON slots(tenant_id);
CREATE INDEX idx_slots_provider_start ON slots(provider_id, start_time);

CREATE TABLE appointments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES clinics(id),
    slot_id               UUID NOT NULL REFERENCES slots(id),
    patient_id            UUID REFERENCES patients(id), -- NULL for guest bookings
    provider_id           UUID NOT NULL REFERENCES providers(id),
    appointment_type_id   UUID NOT NULL REFERENCES appointment_types(id),
    channel               VARCHAR(20) NOT NULL, -- patient_portal, front_desk, guest
    -- booked -> checked_in -> roomed -> with_provider -> checked_out, plus no_show/cancelled
    status                VARCHAR(20) NOT NULL DEFAULT 'booked',
    appointment_ref       VARCHAR(6) NOT NULL UNIQUE,
    clinic_ref            VARCHAR(30),
    customer_user_id      UUID REFERENCES app_users(id), -- set for patient_portal bookings only
    contact_name          VARCHAR(255),
    contact_phone         VARCHAR(30),
    contact_email         VARCHAR(255), -- guest contact only; never persisted for guests with no recipient
    idempotency_key       VARCHAR(64) NOT NULL,
    booked_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at          TIMESTAMPTZ,
    cancellation_reason   VARCHAR(255),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_appointments_tenant ON appointments(tenant_id);
CREATE INDEX idx_appointments_patient ON appointments(patient_id);
CREATE INDEX idx_appointments_provider ON appointments(provider_id);
CREATE UNIQUE INDEX idx_appointments_clinic_ref ON appointments(tenant_id, clinic_ref) WHERE clinic_ref IS NOT NULL;

-- Audit row per reschedule (patient/contact details are carried unchanged on
-- the appointment itself - only the slot moves).
CREATE TABLE appointment_reschedules (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES clinics(id),
    appointment_id        UUID NOT NULL REFERENCES appointments(id),
    previous_slot_id      UUID NOT NULL REFERENCES slots(id),
    fee_amount            NUMERIC(10,2) NOT NULL DEFAULT 0,
    reason                VARCHAR(255),
    created_by            UUID REFERENCES app_users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_appointment_reschedules_tenant ON appointment_reschedules(tenant_id);

-- Clinic-configurable no-show/late-cancel fee tiers, keyed by hours-before-
-- appointment. provider_id NULL = clinic-wide default; a specific
-- provider_id overrides it. No row for a clinic => fee is zero (a missing
-- policy is a config gap, not grounds to block a cancellation).
CREATE TABLE fee_policies (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES clinics(id),
    provider_id  UUID REFERENCES providers(id),
    cutoff_hours INT NOT NULL,
    fee_percent  INT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fee_policies_tenant ON fee_policies(tenant_id);

-- One lazy singleton row per clinic, created on first PATCH. Fee fields here
-- are the flat RESCHEDULE mutation fee only - the tiered no-show/late-cancel
-- fee lives entirely in fee_policies above, with no duplication.
CREATE TABLE clinic_settings (
    tenant_id                       UUID PRIMARY KEY REFERENCES clinics(id),
    tax_rate_percent                NUMERIC(5,2),
    reschedule_fee_patient_portal   NUMERIC(10,2),
    reschedule_fee_front_desk       NUMERIC(10,2),
    reschedule_min_notice_hours     INT,
    appointment_reminder_lead_hours INT,
    support_phone                   VARCHAR(30),
    support_email                   VARCHAR(255),
    address                         VARCHAR(255),
    website                         VARCHAR(255),
    logo_url                        VARCHAR(500),
    brand_color                     VARCHAR(20),
    accent_color                    VARCHAR(20),
    display_name                    VARCHAR(255),
    footer_note                     VARCHAR(500),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    appointment_id    UUID NOT NULL UNIQUE REFERENCES appointments(id),
    subtotal_amount   NUMERIC(10,2) NOT NULL,
    tax_amount        NUMERIC(10,2) NOT NULL,
    total_amount      NUMERIC(10,2) NOT NULL, -- = subtotal_amount + tax_amount
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_invoices_tenant ON invoices(tenant_id);

-- Recording a payment is a deliberate, separate staff action - creating an
-- appointment doesn't create or require one, and cancelling doesn't auto-void
-- one. appointment_id stays NOT NULL in v1; it only goes nullable when the
-- lab-orders module (later) adds its own owner column + CHECK constraint.
CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    appointment_id  UUID NOT NULL REFERENCES appointments(id),
    amount          NUMERIC(10,2) NOT NULL,
    method          VARCHAR(20) NOT NULL, -- cash, card, mobile_money, insurance
    transaction_id  VARCHAR(120),
    recorded_by     UUID REFERENCES app_users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_tenant ON payments(tenant_id);

CREATE TABLE encounters (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    appointment_id    UUID NOT NULL UNIQUE REFERENCES appointments(id),
    provider_id       UUID NOT NULL REFERENCES providers(id),
    chief_complaint   TEXT,
    assessment        TEXT,
    plan              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_encounters_tenant ON encounters(tenant_id);

CREATE TABLE prescriptions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    encounter_id      UUID NOT NULL REFERENCES encounters(id),
    medication_name   VARCHAR(255) NOT NULL,
    dosage            VARCHAR(120),
    instructions      TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prescriptions_tenant ON prescriptions(tenant_id);

-- Outbox pattern: written in the same transaction as the triggering write,
-- dispatched asynchronously by NotificationWorker so a flaky email provider
-- never fails a booking/reschedule/etc.
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES clinics(id),
    recipient   VARCHAR(255) NOT NULL,
    channel     VARCHAR(20) NOT NULL DEFAULT 'email',
    type        VARCHAR(50) NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending, sent, failed
    attempts    INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX idx_notifications_tenant ON notifications(tenant_id);
CREATE INDEX idx_notifications_status ON notifications(status);
