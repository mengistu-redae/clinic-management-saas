-- Phase 25 of the revised (EHR-leaning) phase plan: physical-exam
-- findings. Extends the existing encounters table rather than introducing
-- a new one - same "columns directly on Encounter" shape icd10_codes
-- (V10) already used, since the access gate and 1:1-per-encounter
-- cardinality here are identical to Encounter's own. A fixed 9-system
-- review-of-systems checklist (general appearance, HEENT, cardiovascular,
-- respiratory, abdominal, musculoskeletal, neurological, skin,
-- psychiatric), each a nullable normal/abnormal flag (null = not
-- examined) plus a free-text note.

ALTER TABLE encounters
    ADD COLUMN general_appearance_normal  BOOLEAN,
    ADD COLUMN general_appearance_note    TEXT,
    ADD COLUMN heent_normal               BOOLEAN,
    ADD COLUMN heent_note                 TEXT,
    ADD COLUMN cardiovascular_normal      BOOLEAN,
    ADD COLUMN cardiovascular_note        TEXT,
    ADD COLUMN respiratory_normal         BOOLEAN,
    ADD COLUMN respiratory_note           TEXT,
    ADD COLUMN abdominal_normal           BOOLEAN,
    ADD COLUMN abdominal_note             TEXT,
    ADD COLUMN musculoskeletal_normal     BOOLEAN,
    ADD COLUMN musculoskeletal_note       TEXT,
    ADD COLUMN neurological_normal        BOOLEAN,
    ADD COLUMN neurological_note          TEXT,
    ADD COLUMN skin_normal                BOOLEAN,
    ADD COLUMN skin_note                  TEXT,
    ADD COLUMN psychiatric_normal         BOOLEAN,
    ADD COLUMN psychiatric_note           TEXT;
