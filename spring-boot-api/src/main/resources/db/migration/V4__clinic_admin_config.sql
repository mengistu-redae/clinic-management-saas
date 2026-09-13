-- Phase 5: clinic-admin config. rooms/appointment_types get the same
-- soft-deactivate `status` column clinics/providers/appointments already
-- use (rather than the reference project's boolean `active` flag) - see
-- CLAUDE.md's phase 5 write-up for why. clinic_settings/fee_policies/
-- providers/provider_working_hours already have everything phase 5 needs
-- since V1__init.sql - nothing else to add here.

ALTER TABLE rooms ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
ALTER TABLE appointment_types ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
