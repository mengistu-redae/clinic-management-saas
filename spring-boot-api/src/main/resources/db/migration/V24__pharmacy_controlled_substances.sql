-- Phase 28 (pharmacy expansion): controlled substance tracking. A
-- Medication can now carry a controlled-substance schedule; dispensing
-- one no longer goes through the plain single-step dispense path at all
-- - it goes through a new, separate, mutable two-phase workflow (a
-- pharmacist requests it, a *different* pharmacist or clinic_admin
-- co-signs before the real stock decrement/DispenseRecord happens).
-- DispenseRecord itself stays genuinely append-only - the mutable,
-- in-progress state lives entirely in the new workflow table below.

ALTER TABLE medications
    ADD COLUMN controlled_substance_schedule VARCHAR(20);

-- Set once at creation, never updated after - the existing append-only
-- invariant on dispense_records holds exactly as already documented.
-- Null for every ordinary (non-controlled) dispense.
ALTER TABLE dispense_records
    ADD COLUMN co_signed_by UUID REFERENCES app_users(id);

-- The workflow entity - genuinely mutable (status transitions), unlike
-- dispense_records. Same FK style dispense_records already uses.
CREATE TABLE pending_controlled_substance_dispenses (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                     UUID NOT NULL REFERENCES clinics(id),
    prescription_id               UUID NOT NULL REFERENCES prescriptions(id),
    medication_id                 UUID NOT NULL REFERENCES medications(id),
    stock_batch_id                UUID NOT NULL REFERENCES stock_batches(id),
    quantity                      INTEGER NOT NULL,
    notes                         TEXT,
    safety_override_acknowledged  BOOLEAN NOT NULL DEFAULT false,
    -- pending, cosigned, rejected
    status                        VARCHAR(20) NOT NULL DEFAULT 'pending',
    requested_by                  UUID NOT NULL REFERENCES app_users(id),
    requested_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    co_signed_by                  UUID REFERENCES app_users(id),
    co_signed_at                  TIMESTAMPTZ,
    -- Set once cosigned - direct traceability from this workflow row to
    -- the permanent record it produced.
    dispense_record_id            UUID REFERENCES dispense_records(id),
    rejected_by                   UUID REFERENCES app_users(id),
    rejected_at                   TIMESTAMPTZ,
    rejection_reason              TEXT,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_csd_tenant ON pending_controlled_substance_dispenses(tenant_id);
CREATE INDEX idx_pending_csd_prescription ON pending_controlled_substance_dispenses(prescription_id);
