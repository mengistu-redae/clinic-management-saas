-- Phase 7: lab orders module. A test only becomes orderable once
-- clinic_admin configures a lab_test_rates row for its test_code - no
-- separate fixed "lab test catalog" table, same "config implies
-- availability" shape fee_policies already uses.

CREATE TABLE lab_test_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    test_code       VARCHAR(50) NOT NULL,
    base_charge     NUMERIC(10,2) NOT NULL,
    collection_fee  NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, test_code)
);

CREATE TABLE lab_orders (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES clinics(id),
    patient_id             UUID NOT NULL REFERENCES patients(id),
    encounter_id           UUID REFERENCES encounters(id),
    -- Null until a "requested" (patient-initiated) order is confirmed.
    ordering_provider_id   UUID REFERENCES providers(id),
    -- Set only for a patient-initiated request - mirrors appointments.customer_user_id.
    customer_user_id       UUID REFERENCES app_users(id),
    notes                  TEXT,
    priority               VARCHAR(20) NOT NULL DEFAULT 'routine',
    -- requested, ordered, specimen_collected, in_transit, resulted, reviewed, cancelled
    status                 VARCHAR(20) NOT NULL DEFAULT 'ordered',
    order_ref              VARCHAR(6) NOT NULL UNIQUE,
    clinic_ref             VARCHAR(30),
    -- Snapshotted at order-creation (or confirm-and-order) time - a later
    -- lab_test_rates change never re-prices an issued order.
    total_cost             NUMERIC(10,2),
    ordered_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    specimen_collected_at  TIMESTAMPTZ,
    sent_at                TIMESTAMPTZ,
    resulted_at            TIMESTAMPTZ,
    resulted_by            UUID REFERENCES app_users(id),
    reviewed_at            TIMESTAMPTZ,
    reviewed_by            UUID REFERENCES app_users(id),
    cancelled_at           TIMESTAMPTZ,
    cancellation_reason    VARCHAR(255),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lab_orders_tenant ON lab_orders(tenant_id);
CREATE INDEX idx_lab_orders_customer_user ON lab_orders(customer_user_id);

CREATE TABLE lab_order_tests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    lab_order_id    UUID NOT NULL REFERENCES lab_orders(id),
    -- Null on a patient-initiated request until confirm-and-order fills it in.
    test_code       VARCHAR(50),
    test_name       VARCHAR(255) NOT NULL,
    specimen_type   VARCHAR(100),
    notes           TEXT,
    -- Snapshotted per line item at pricing time - null until priced.
    price           NUMERIC(10,2),
    result_value    VARCHAR(255),
    result_unit     VARCHAR(50),
    reference_range VARCHAR(120),
    abnormal_flag   BOOLEAN,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lab_order_tests_order ON lab_order_tests(lab_order_id);

-- Mirrors appointment_cancellations exactly (phase 3).
CREATE TABLE lab_order_cancellations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    lab_order_id  UUID NOT NULL REFERENCES lab_orders(id),
    cancelled_by  UUID REFERENCES app_users(id),
    reason        VARCHAR(255),
    fee_amount    NUMERIC(10,2) NOT NULL DEFAULT 0,
    cancelled_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Payments has sat unused since V1 - relaxing appointment_id to nullable
-- and adding lab_order_id + a CHECK enforcing exactly one owner, so the
-- same table serves both an appointment payment and a lab-order payment,
-- never both/neither.
ALTER TABLE payments ALTER COLUMN appointment_id DROP NOT NULL;
ALTER TABLE payments ADD COLUMN lab_order_id UUID REFERENCES lab_orders(id);
ALTER TABLE payments ADD CONSTRAINT chk_payments_exactly_one_owner
    CHECK ((appointment_id IS NOT NULL) <> (lab_order_id IS NOT NULL));
