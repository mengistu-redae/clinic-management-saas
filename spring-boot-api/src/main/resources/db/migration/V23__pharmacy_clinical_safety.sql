-- Phase 27 (pharmacy expansion): clinical safety checks. Before a
-- dispense proceeds, DispenseService cross-checks the medication against
-- the patient's own active allergies and against a new self-maintained
-- drug_interaction_pairs table (no licensed external drug database is
-- available to this app, so interaction data is whatever the clinic
-- itself adds). Neither check hard-blocks - an explicit acknowledgment
-- flag on the dispense request lets a pharmacist proceed past a detected
-- conflict, recorded here so the override is auditable.

-- Self-maintained, tenant-scoped - pure config with a well-defined
-- "missing = no known interaction" fallback, same reasoning fee_policies/
-- lab_test_rates already use for their own hard-delete convention.
CREATE TABLE drug_interaction_pairs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    medication_a_id   UUID NOT NULL REFERENCES medications(id),
    medication_b_id   UUID NOT NULL REFERENCES medications(id),
    -- mild, moderate, severe - same convention as allergies.severity
    severity          VARCHAR(20),
    description       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drug_interaction_pairs_tenant ON drug_interaction_pairs(tenant_id);

-- Set once at creation, never updated after - dispense_records stays
-- genuinely append-only exactly as already documented on that table.
ALTER TABLE dispense_records
    ADD COLUMN safety_override_acknowledged BOOLEAN NOT NULL DEFAULT false;
