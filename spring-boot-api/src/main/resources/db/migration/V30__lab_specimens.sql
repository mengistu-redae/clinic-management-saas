-- Lab module L1 (sketched in CLAUDE.md 2026-10-02): the lab_orders status
-- machine stays exactly as it is (collect-specimen/send/result/review all
-- keep flipping lab_orders.status the same way they always have) - this is
-- a purely additive, finer-grained tracking layer underneath it, the same
-- "new fulfillment-tracking entity, existing resource untouched" shape
-- Prescription/DispenseRecord already established for pharmacy.
--
-- One lab_order can require more than one physical specimen (e.g. blood +
-- urine on one order) - specimens are derived from the distinct
-- specimen_type values already present on that order's own lab_order_tests
-- rows, one Specimen per distinct type, not entered by hand.

CREATE TABLE specimens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    lab_order_id      UUID NOT NULL REFERENCES lab_orders(id),
    specimen_type     VARCHAR(100) NOT NULL,
    -- pending_collection, collected, in_transit, received, processing, completed, rejected
    status            VARCHAR(30) NOT NULL DEFAULT 'pending_collection',
    collected_at      TIMESTAMPTZ,
    collected_by      UUID REFERENCES app_users(id),
    received_at       TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    rejected_at       TIMESTAMPTZ,
    rejection_reason  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_specimens_tenant ON specimens(tenant_id);
CREATE INDEX idx_specimens_lab_order ON specimens(lab_order_id);

-- Nullable - a test line only links to a specimen once one's been derived
-- for its own specimen_type (and a blank/unset specimen_type on a test
-- line, which the app already allows today, never gets one at all).
ALTER TABLE lab_order_tests ADD COLUMN specimen_id UUID REFERENCES specimens(id);
