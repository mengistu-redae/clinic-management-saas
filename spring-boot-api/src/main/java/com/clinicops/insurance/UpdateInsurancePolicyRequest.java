package com.clinicops.insurance;

import java.time.LocalDate;

/** Partial update - only non-null fields applied, same convention as UpdateAllergyRequest. patientId/payerName/memberId are fixed at creation; correct via a new row instead. */
public record UpdateInsurancePolicyRequest(
        String groupNumber,
        String planType,
        String rank,
        String subscriberName,
        String relationshipToSubscriber,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        String status
) {
}
