-- Phase 45: shared patient records across branches of the same clinic chain.
-- Purely additive/opt-in - every new column is nullable, so every existing
-- clinic/patient keeps today's exact behavior until a platform_admin
-- explicitly creates a group and assigns clinics to it. See CLAUDE.md's
-- "Phase 45" write-up for the full design reasoning.

CREATE TABLE clinic_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE clinics ADD COLUMN clinic_group_id UUID REFERENCES clinic_groups(id);

-- Denormalized copy from the creating clinic's group at creation time (same
-- "copy, don't join" precedent Encounter already set for providerId/tenantId)
-- - not kept live-in-sync if a clinic's own group assignment changes later.
ALTER TABLE patients ADD COLUMN clinic_group_id UUID REFERENCES clinic_groups(id);
CREATE INDEX idx_patients_clinic_group_id ON patients(clinic_group_id) WHERE clinic_group_id IS NOT NULL;
