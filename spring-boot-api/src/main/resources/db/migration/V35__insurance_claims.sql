-- Insurance & claims billing (sketched 2026-10-03) - the first module
-- targeting real third-party-payer billing. Everything before this
-- migration (payments/invoices) models cash/self-pay only; Payment.method
-- has allowed the literal string "insurance" since phase 1 but nothing
-- ever tracked a claim's own lifecycle behind that label.
--
-- Self-contained tracker, not a real clearinghouse/EDI integration - no
-- real payer credentials exist in this dev environment, same "mock/defer
-- the real vendor" call phases 16 (payment gateway) and 17 (email) already
-- made. Claims are billed against an already-issued Invoice (read-only
-- reference, not a new Invoice/Payment owner type - no change to either
-- table's own exactly-one-owner CHECK).

CREATE TABLE insurance_policies (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID NOT NULL REFERENCES clinics(id),
    patient_id                 UUID NOT NULL REFERENCES patients(id),
    payer_name                 VARCHAR(200) NOT NULL,
    member_id                  VARCHAR(100) NOT NULL,
    group_number               VARCHAR(100),
    plan_type                  VARCHAR(50),
    -- primary, secondary - coordination of benefits: a claim can bill a
    -- secondary payer for whatever the primary didn't cover.
    rank                       VARCHAR(20) NOT NULL DEFAULT 'primary',
    subscriber_name            VARCHAR(200),
    -- self, spouse, child, other
    relationship_to_subscriber VARCHAR(20) NOT NULL DEFAULT 'self',
    effective_date             DATE,
    expiration_date            DATE,
    -- active, inactive - no delete endpoint, correcting a mistaken entry
    -- means adding a new row and marking the old one inactive, same
    -- precedent Allergy/Provider/Room already set for safety/financial
    -- records.
    status                     VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_insurance_policies_tenant_patient ON insurance_policies(tenant_id, patient_id);

CREATE TABLE claims (
    id                             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                      UUID NOT NULL REFERENCES clinics(id),
    invoice_id                     UUID NOT NULL REFERENCES invoices(id),
    insurance_policy_id            UUID NOT NULL REFERENCES insurance_policies(id),
    -- Snapshotted directly rather than resolved through invoice -> its
    -- owner (appointment/lab_order/dispense_record) on every read - same
    -- "embed what the caller needs" convention PrescriptionDispenseView/
    -- RefillRequestQueueEntry already established.
    patient_id                     UUID NOT NULL REFERENCES patients(id),
    -- the payer's own claim reference, set once at submission
    claim_number                   VARCHAR(100),
    -- draft, submitted, paid, partially_paid, denied, appealed, closed
    status                         VARCHAR(20) NOT NULL DEFAULT 'draft',
    billed_amount                  NUMERIC(12,2) NOT NULL,
    allowed_amount                 NUMERIC(12,2),
    paid_amount                    NUMERIC(12,2),
    patient_responsibility_amount  NUMERIC(12,2),
    denial_reason                  TEXT,
    appeal_reason                  TEXT,
    notes                          TEXT,
    created_by                     UUID REFERENCES app_users(id),
    submitted_at                   TIMESTAMPTZ,
    adjudicated_at                 TIMESTAMPTZ,
    appealed_at                    TIMESTAMPTZ,
    closed_at                      TIMESTAMPTZ,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claims_tenant_invoice ON claims(tenant_id, invoice_id);
CREATE INDEX idx_claims_tenant_patient ON claims(tenant_id, patient_id);
