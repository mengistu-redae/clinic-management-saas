-- Phase 18: per-clinic timezone override. Nullable - null means "use the
-- platform default" (ClinicSettingsService.resolveTimezone), same as every
-- other business field in the clinic_settings "settings" column group.
-- A valid IANA zone id (e.g. 'Africa/Addis_Ababa'), validated in code
-- (ClinicSettingsService), not by a DB constraint - same convention as
-- Allergy.severity/Prescription.route elsewhere in this app.
ALTER TABLE clinic_settings ADD COLUMN timezone VARCHAR(64);
