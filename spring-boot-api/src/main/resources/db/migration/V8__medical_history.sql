-- Phase 9 of the revised (EHR-leaning) phase plan: persistent, patient-
-- level medical history - past conditions, home medications, family/
-- social history. Deliberately does NOT duplicate known_allergies
-- (allergies already has its own structured table since phase 8) or
-- chief_complaint (that's per-encounter, on encounters, not a standing
-- record) - see MedicalHistory's own javadoc.

CREATE TABLE medical_history (
    patient_id           UUID PRIMARY KEY REFERENCES patients(id),
    tenant_id            UUID NOT NULL REFERENCES clinics(id),
    past_conditions      TEXT,
    past_surgeries       TEXT,
    current_medications  TEXT,
    family_history       TEXT,
    social_history       TEXT,
    recorded_by          UUID REFERENCES app_users(id),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_medical_history_tenant ON medical_history(tenant_id);
