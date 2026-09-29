-- Phase 33 (pharmacy expansion): patient-facing pharmacy features. A
-- lightweight refill-request workflow - reuses LabOrder's own phase-7
-- patient-request -> staff-confirm shape rather than inventing a new
-- pattern. requested_by is always set - only a patient can create one.

CREATE TABLE prescription_refill_requests (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    prescription_id UUID NOT NULL REFERENCES prescriptions(id),
    patient_id      UUID NOT NULL REFERENCES patients(id),
    requested_by    UUID NOT NULL REFERENCES app_users(id),
    notes           TEXT,
    -- requested, approved, denied
    status          VARCHAR(20) NOT NULL DEFAULT 'requested',
    reviewed_by     UUID REFERENCES app_users(id),
    reviewed_at     TIMESTAMPTZ,
    review_notes    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prescription_refill_requests_tenant ON prescription_refill_requests(tenant_id);
CREATE INDEX idx_prescription_refill_requests_prescription ON prescription_refill_requests(prescription_id);
CREATE INDEX idx_prescription_refill_requests_requested_by ON prescription_refill_requests(requested_by);
