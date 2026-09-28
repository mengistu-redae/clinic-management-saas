-- Phase 24 of the revised (EHR-leaning) phase plan: immunizations - a
-- simple per-patient vaccine administration log. No due-date/schedule
-- tracking (a deliberate scope boundary, not deferred) - just what was
-- actually given, when, and by whom.

-- Patient-level, not encounter-level - a vaccination isn't tied to one
-- specific visit the way vitals are, and accumulates as a list over time
-- the same shape as allergies. No status column - unlike an allergy (an
-- evolving condition), each row is a discrete historical fact with
-- nothing to resolve/unconfirm. No delete endpoint (ImmunizationController) -
-- correcting a mistaken entry means a partial update to the non-identity
-- fields (dose_number/lot_number/site), same "safety/history data isn't
-- erased" precedent allergies/providers/rooms already set.
CREATE TABLE immunizations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES clinics(id),
    patient_id        UUID NOT NULL REFERENCES patients(id),
    vaccine_name      VARCHAR(255) NOT NULL,
    administered_at   DATE NOT NULL,
    dose_number       INT,
    lot_number        VARCHAR(100),
    site              VARCHAR(100),
    recorded_by       UUID REFERENCES app_users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_immunizations_tenant ON immunizations(tenant_id);
CREATE INDEX idx_immunizations_patient ON immunizations(patient_id);
