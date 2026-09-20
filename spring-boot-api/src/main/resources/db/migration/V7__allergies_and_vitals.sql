-- Phase 8 of the revised (EHR-leaning) phase plan: patient-safety
-- fundamentals - allergies (persistent, patient-level) and vitals
-- (per-visit, appointment-level).

-- Patient-level, not encounter-level - an allergy is part of the patient's
-- standing health record, not tied to one specific visit. Correcting a
-- mistaken entry means adding a new row and marking the old one
-- resolved/unconfirmed (AllergyController has no delete endpoint) - never
-- an outright delete, same "safety/history data isn't erased" precedent
-- providers/rooms already set with their own soft-deactivate.
CREATE TABLE allergies (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES clinics(id),
    patient_id     UUID NOT NULL REFERENCES patients(id),
    allergen       VARCHAR(255) NOT NULL,
    reaction_type  VARCHAR(255),
    severity       VARCHAR(20) NOT NULL DEFAULT 'moderate', -- mild, moderate, severe
    status         VARCHAR(20) NOT NULL DEFAULT 'active', -- active, resolved, unconfirmed
    identified_at  DATE,
    recorded_by    UUID REFERENCES app_users(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_allergies_tenant ON allergies(tenant_id);
CREATE INDEX idx_allergies_patient ON allergies(patient_id);

-- One row per appointment (appointment_id unique, same "exactly one per
-- visit" shape as encounters.appointment_id) - not tied to an Encounter
-- row, deliberately: vitals are typically taken at check-in/roomed, before
-- a provider has necessarily started (or ever will start) documenting a
-- clinical note, and unlike Encounter this is writable by front_desk too
-- (no "nurse" role exists in this app) - front_desk has no access to
-- Encounter content at all, so vitals can't hang off it without either
-- granting front_desk clinical-note access or duplicating data.
CREATE TABLE vitals (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL REFERENCES clinics(id),
    appointment_id            UUID NOT NULL UNIQUE REFERENCES appointments(id),
    height_cm                 NUMERIC(5,1),
    weight_kg                 NUMERIC(5,1),
    temperature_c             NUMERIC(4,1),
    pulse_bpm                 INT,
    respiratory_rate          INT,
    blood_pressure_systolic   INT,
    blood_pressure_diastolic  INT,
    oxygen_saturation_pct     NUMERIC(4,1),
    pain_score                INT CHECK (pain_score IS NULL OR pain_score BETWEEN 0 AND 10),
    recorded_by               UUID REFERENCES app_users(id),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_vitals_tenant ON vitals(tenant_id);
