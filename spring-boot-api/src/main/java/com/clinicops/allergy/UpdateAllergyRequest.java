package com.clinicops.allergy;

/** Partial update - only non-null fields applied, same convention as UpdateRoomRequest/UpdateProviderRequest. No way to change patientId/allergen once created; correct via a new row instead. */
public record UpdateAllergyRequest(
        String reactionType,
        String severity,
        String status
) {
}
