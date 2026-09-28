-- Phase 26 (visit/encounter summary document) needs to answer "which
-- immunizations were given at this specific visit" - Immunization (phase
-- 24) was built patient-level only, with no way to tie a dose to the
-- appointment it was given at. Closing that gap here rather than working
-- around it in the summary itself (showing unrelated full history, or
-- dropping the section, would both be worse).

ALTER TABLE immunizations
    ADD COLUMN appointment_id UUID REFERENCES appointments(id);
