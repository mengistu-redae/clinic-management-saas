package com.clinicops.immunization;

/** Partial update - only non-null fields applied, same convention as UpdateAllergyRequest. No way to change vaccineName/administeredAt/patientId once created; correct via a new row instead. */
public record UpdateImmunizationRequest(
        Integer doseNumber,
        String lotNumber,
        String site
) {
}
