package com.clinicops.insurance;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateInsurancePolicyRequest(
        @NotBlank String payerName,
        @NotBlank String memberId,
        String groupNumber,
        String planType,
        String rank,
        String subscriberName,
        String relationshipToSubscriber,
        LocalDate effectiveDate,
        LocalDate expirationDate
) {
}
