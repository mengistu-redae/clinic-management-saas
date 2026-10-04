-- Patient engagement, secure messaging (sketched 2026-10-04) - second of
-- three sequential patient-engagement phases (SMS reminders built, this
-- one, then satisfaction surveys). Four direct questions were pinned
-- before writing any code: one ongoing thread per patient (not a new
-- thread per topic), a shared clinic inbox (any provider/clinic_admin can
-- see and reply - no front_desk access, no patient-picks-a-provider
-- step), and text-only for v1 (no attachments).
--
-- Deliberately flat - no separate "thread" entity, since "one ongoing
-- thread per patient" means the thread IS just that patient's own
-- messages in order; nothing else needs its own row.

CREATE TABLE patient_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES clinics(id),
    patient_id      UUID NOT NULL REFERENCES patients(id),
    -- patient, staff
    sender_type     VARCHAR(10) NOT NULL,
    sender_user_id  UUID REFERENCES app_users(id),
    body            TEXT NOT NULL,
    -- set when the OPPOSITE party (staff for a patient-sent message, the
    -- patient for a staff-sent one) has viewed it - drives the staff
    -- inbox's own unread count.
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_patient_messages_tenant_patient ON patient_messages(tenant_id, patient_id);
