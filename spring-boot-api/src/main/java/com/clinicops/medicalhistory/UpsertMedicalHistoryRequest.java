package com.clinicops.medicalhistory;

/** Full-replace on every call, same "no partial-update semantics for a single-row-per-owner record" shape as UpsertVitalsRequest - all fields optional since not every field is captured at every intake. */
public record UpsertMedicalHistoryRequest(
        String pastConditions,
        String pastSurgeries,
        String currentMedications,
        String familyHistory,
        String socialHistory
) {
}
