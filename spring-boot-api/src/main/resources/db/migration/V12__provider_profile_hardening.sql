-- Phase 13 of the revised (EHR-leaning) phase plan: provider profile
-- hardening - license number/expiry, employment status, and a digital
-- signature image (the first feature in this app to use real file
-- storage - local disk + a Docker volume, decided in phase 9 ahead of
-- the phase that would first need it). signature_filename/
-- signature_content_type describe the file on disk
-- (<uploads-root>/provider-signatures/<providerId>.<ext>); the bytes
-- themselves never live in Postgres.

ALTER TABLE providers
    ADD COLUMN license_number      VARCHAR(100),
    ADD COLUMN license_expiry      DATE,
    ADD COLUMN employment_status   VARCHAR(20), -- full_time, part_time, locum
    ADD COLUMN signature_filename      VARCHAR(255),
    ADD COLUMN signature_content_type  VARCHAR(50);
