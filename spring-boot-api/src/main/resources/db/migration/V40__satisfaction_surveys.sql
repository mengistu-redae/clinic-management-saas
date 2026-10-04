-- Phase 44: patient satisfaction surveys - third and last of the three
-- sequential patient-engagement phases (SMS reminders V36, secure
-- messaging V39, now this). One row auto-created per appointment the
-- moment it's checked out (CheckInService.checkOut); rating/comment/
-- submitted_at all start null and are filled in once by the patient.
CREATE TABLE satisfaction_surveys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES clinics(id),
    appointment_id UUID NOT NULL UNIQUE REFERENCES appointments(id),
    rating         INT CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    comment        TEXT,
    submitted_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_satisfaction_surveys_tenant ON satisfaction_surveys(tenant_id);
