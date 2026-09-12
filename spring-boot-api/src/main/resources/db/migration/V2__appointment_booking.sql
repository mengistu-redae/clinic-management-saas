-- Phase 2 additions on top of V1's base domain schema - see CLAUDE.md's
-- "Phase 2" notes for why each of these wasn't anticipated in V1.

-- Links a patient-portal login (a Keycloak subject, via app_users) to a
-- specific clinic's patient record. Nullable - most patients rows are
-- created by front-desk staff for a walk-in with no portal account at all;
-- set only when a patient books through the portal for the first time at
-- this clinic (see PatientProvisioningService). One portal login maps to at
-- most one patient record per clinic.
ALTER TABLE patients ADD COLUMN app_user_id UUID REFERENCES app_users(id);
CREATE UNIQUE INDEX idx_patients_tenant_app_user
    ON patients(tenant_id, app_user_id) WHERE app_user_id IS NOT NULL;

-- Lets slot generation cheaply check "does this slot already exist" and
-- prevents two concurrent generation calls from double-inserting the same
-- provider/type/time slot.
CREATE UNIQUE INDEX idx_slots_provider_type_start
    ON slots(provider_id, appointment_type_id, start_time);

-- A bounded recurring-appointment series (e.g. "every 2 weeks x6") - see
-- AppointmentSeriesService. Each occurrence is its own real appointments
-- row (booked through the exact same single-slot lock+write path as any
-- other appointment); this table is just the series' own configuration plus
-- an idempotency key for the whole series-creation request.
CREATE TABLE appointment_series (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES clinics(id),
    patient_id            UUID NOT NULL REFERENCES patients(id),
    provider_id           UUID NOT NULL REFERENCES providers(id),
    appointment_type_id   UUID NOT NULL REFERENCES appointment_types(id),
    channel               VARCHAR(20) NOT NULL,
    interval_weeks        INT NOT NULL,
    occurrence_count      INT NOT NULL,
    idempotency_key       VARCHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX idx_appointment_series_tenant ON appointment_series(tenant_id);

ALTER TABLE appointments ADD COLUMN series_id UUID REFERENCES appointment_series(id);
ALTER TABLE appointments ADD COLUMN series_occurrence_index INT;
CREATE INDEX idx_appointments_series ON appointments(series_id);
