-- `domain` was collected at clinic onboarding from day one (CreateClinicRequest),
-- but only ever forwarded to Keycloak's own Organization record - never
-- persisted locally, so platform_admin had no way to look a clinic up by it
-- (found in the 2026-10-02 search/filter audit). Nullable since every
-- existing clinic was provisioned before this column existed, with no
-- domain on file to backfill from.
ALTER TABLE clinics ADD COLUMN domain VARCHAR(255);
