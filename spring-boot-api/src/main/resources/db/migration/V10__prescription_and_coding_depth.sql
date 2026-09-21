-- Phase 11 of the revised (EHR-leaning) phase plan: prescription + coding
-- depth. Extends the existing prescriptions/encounters tables rather than
-- introducing new ones - matches the reference clinical-forms doc's own
-- Form 9 (Prescription/Medication Order), staying within this app's
-- existing per-encounter prescription-list shape (full-replace on every
-- save) rather than reworking it into a cross-encounter ongoing
-- -medication model.

ALTER TABLE prescriptions
    ADD COLUMN route               VARCHAR(20),
    ADD COLUMN frequency           VARCHAR(100),
    ADD COLUMN duration            VARCHAR(100),
    ADD COLUMN quantity_dispensed  INT,
    ADD COLUMN refills_allowed     INT,
    ADD COLUMN status              VARCHAR(20) NOT NULL DEFAULT 'active';

-- Free-text ICD-10 tags (e.g. "I10, E11.9") - deliberately NOT validated
-- against a real ICD-10 code-set table; importing/maintaining tens of
-- thousands of codes is a separate, much bigger later decision than this
-- phase's own "keep minimal" scope (same reasoning the reference doc's
-- own "Data Model Notes" section flags for coding-standard integration).
ALTER TABLE encounters
    ADD COLUMN icd10_codes TEXT;
