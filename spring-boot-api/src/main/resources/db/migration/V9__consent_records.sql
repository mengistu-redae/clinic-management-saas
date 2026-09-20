-- Phase 10 of the revised (EHR-leaning) phase plan: consent & compliance
-- records - general-treatment and privacy/data-protection consent (the
-- reference clinical-forms doc's Forms 4 & 6; procedure-specific consent,
-- Form 5, deferred - see ConsentRecord's own javadoc).
--
-- Genuinely immutable once created - no update/delete anywhere in this
-- app for this table, matching this app's own existing audit-only tables
-- (appointment_cancellations, appointment_reschedules, phi_access_log)
-- and the reference doc's own "this matters for legal defensibility"
-- reasoning for consent records specifically.
--
-- Lightweight: no signature image captured (decided 2026-09-20, even
-- though real file storage - local disk + a Docker volume - is available
-- as of the phase-9 decision) - just an acknowledgment record.

CREATE TABLE consent_records (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES clinics(id),
    patient_id               UUID NOT NULL REFERENCES patients(id),
    consent_type             VARCHAR(20) NOT NULL, -- general_treatment, privacy_data
    policy_version           VARCHAR(50) NOT NULL,
    consent_given            BOOLEAN NOT NULL DEFAULT true,
    witness_name             VARCHAR(255),
    language_presented       VARCHAR(50),
    data_sharing_preferences TEXT,
    signed_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by              UUID REFERENCES app_users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_records_tenant ON consent_records(tenant_id);
CREATE INDEX idx_consent_records_patient ON consent_records(patient_id);
