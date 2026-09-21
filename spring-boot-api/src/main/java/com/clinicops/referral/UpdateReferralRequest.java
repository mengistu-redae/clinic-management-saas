package com.clinicops.referral;

/** Partial update - only non-null fields applied, same convention as UpdateRoomRequest/UpdateProviderRequest. status/priority are allow-listed in ReferralService. patientId/receivingProviderId/external fields are fixed at creation - correct a mistaken referral with a new one instead. */
public record UpdateReferralRequest(
        String status,
        String priority,
        String notes,
        String clinicalSummary
) {
}
