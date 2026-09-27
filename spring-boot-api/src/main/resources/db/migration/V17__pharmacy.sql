-- Phase 20 (pharmacy): a medication catalog, batch/expiry-tracked stock,
-- and dispensing against an existing Prescription (com.clinicops.encounter,
-- phase 4/11) - the first phase of the new pharmacy/accounting/finance
-- module set. Deliberately does NOT touch payments/invoices this phase -
-- see CLAUDE.md's own write-up for the reasoning (matches how lab orders/
-- encounters were built read/write-only before billing was wired in later).

CREATE TABLE medications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    name                VARCHAR(255) NOT NULL,
    -- tablet, capsule, syrup, injection, other
    form                VARCHAR(20) NOT NULL DEFAULT 'tablet',
    unit_of_measure     VARCHAR(40),
    unit_price          NUMERIC(10, 2) NOT NULL DEFAULT 0,
    reorder_threshold   INTEGER NOT NULL DEFAULT 0,
    -- active, inactive - soft-deactivate only, same reasoning as rooms/
    -- appointment_types: referenced by FK from stock_batches/
    -- dispense_records with no cascade, so a real delete would fail once
    -- anything references it.
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_medications_tenant ON medications(tenant_id);

CREATE TABLE stock_batches (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    medication_id       UUID NOT NULL REFERENCES medications(id),
    batch_number        VARCHAR(100),
    quantity_received   INTEGER NOT NULL,
    quantity_on_hand    INTEGER NOT NULL,
    expiry_date         DATE,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- active, depleted, expired, recalled - no delete endpoint at all,
    -- same "status transitions, not delete" precedent as allergies -
    -- real inventory/audit weight.
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    write_off_reason    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stock_batches_tenant ON stock_batches(tenant_id);
CREATE INDEX idx_stock_batches_medication ON stock_batches(medication_id);

-- Genuinely append-only - no updated_at, same shape as
-- appointment_cancellations/consent_records/phi_access_log. The
-- pharmacist explicitly picks which batch to consume (stock_batch_id is
-- required, not derived) - deliberately no automatic FEFO allocation.
CREATE TABLE dispense_records (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES clinics(id),
    prescription_id     UUID NOT NULL REFERENCES prescriptions(id),
    medication_id       UUID NOT NULL REFERENCES medications(id),
    stock_batch_id      UUID NOT NULL REFERENCES stock_batches(id),
    quantity_dispensed  INTEGER NOT NULL,
    dispensed_by        UUID REFERENCES app_users(id),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_dispense_records_tenant ON dispense_records(tenant_id);
CREATE INDEX idx_dispense_records_prescription ON dispense_records(prescription_id);
