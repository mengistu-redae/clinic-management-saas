package com.clinicops.pharmacy;

/** Partial update - severity/description only. medicationAId/medicationBId are fixed at creation; correct a wrong pair with delete+recreate instead. */
public record UpdateDrugInteractionPairRequest(
        String severity,
        String description
) {
}
