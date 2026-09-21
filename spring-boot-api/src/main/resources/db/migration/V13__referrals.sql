-- Phase 14 of the revised (EHR-leaning) phase plan: referrals - both
-- internal (provider-to-provider, same clinic) and external (referred out
-- to another clinic/specialist), the reference clinical-forms doc's Forms
-- 21 and 12 respectively. One table, not two - internal vs. external is
-- distinguished by which nullable field group is populated (see
-- Referral's own javadoc), matching this app's own precedent for two
-- closely-overlapping shapes (Payment's appointment_id/lab_order_id).

CREATE TABLE referrals (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL REFERENCES clinics(id),
    patient_id                UUID NOT NULL REFERENCES patients(id),
    encounter_id              UUID REFERENCES encounters(id),
    referring_provider_id     UUID NOT NULL REFERENCES providers(id),
    -- Internal referral: set. External referral: left null.
    receiving_provider_id     UUID REFERENCES providers(id),
    -- External referral: at least one of these set. Internal: both null.
    external_provider_name    VARCHAR(255),
    external_clinic_name      VARCHAR(255),
    referred_to_specialty     VARCHAR(120),
    reason                    TEXT NOT NULL,
    clinical_summary          TEXT,
    priority                  VARCHAR(20) NOT NULL DEFAULT 'routine', -- routine, urgent
    -- pending -> accepted -> scheduled -> completed, or declined
    status                    VARCHAR(20) NOT NULL DEFAULT 'pending',
    notes                     TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at              TIMESTAMPTZ
);
CREATE INDEX idx_referrals_tenant ON referrals(tenant_id);
CREATE INDEX idx_referrals_patient ON referrals(patient_id);
