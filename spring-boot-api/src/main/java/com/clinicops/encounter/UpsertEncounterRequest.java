package com.clinicops.encounter;

/** All fields optional, matching the nullable encounters.chief_complaint/assessment/plan columns. */
public record UpsertEncounterRequest(String chiefComplaint, String assessment, String plan) {
}
