package com.clinicops.encounter;

/** All fields optional, matching the nullable encounters.chief_complaint/assessment/plan/icd10_codes columns. icd10Codes is free text (phase 11) - see Encounter's own javadoc. */
public record UpsertEncounterRequest(String chiefComplaint, String assessment, String plan, String icd10Codes) {
}
