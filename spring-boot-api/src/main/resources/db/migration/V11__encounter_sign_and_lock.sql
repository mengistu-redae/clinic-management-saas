-- Phase 12 of the revised (EHR-leaning) phase plan: sign-and-lock clinical
-- notes. Reverses the phase-4 pinned decision that encounters/
-- prescriptions "stay editable indefinitely" - once a provider signs an
-- encounter, both it and its prescription list lock; further changes go
-- through a new append-only addendum, never a direct edit. Explicit
-- sign-off from the user was obtained before building this (see CLAUDE.md's
-- own phase-12 write-up) since it changes existing behavior, not just adds
-- new fields the way phases 8/9/11 did.

ALTER TABLE encounters
    ADD COLUMN signed_at TIMESTAMPTZ,
    ADD COLUMN signed_by UUID REFERENCES app_users(id);

-- Append-only - no update/delete anywhere in this app for this table,
-- same "genuinely immutable" precedent as consent_records/phi_access_log.
-- Only creatable once the parent encounter is signed
-- (EncounterService.addAddendum) - before signing, a provider just edits
-- the encounter directly via the normal upsert.
CREATE TABLE encounter_addenda (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES clinics(id),
    encounter_id  UUID NOT NULL REFERENCES encounters(id),
    author_id     UUID REFERENCES app_users(id),
    text          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_encounter_addenda_tenant ON encounter_addenda(tenant_id);
CREATE INDEX idx_encounter_addenda_encounter ON encounter_addenda(encounter_id);
