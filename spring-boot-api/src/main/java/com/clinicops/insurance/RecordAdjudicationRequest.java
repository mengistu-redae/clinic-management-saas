package com.clinicops.insurance;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/** outcome: paid, partially_paid, or denied. denialReason is required when outcome is denied; the three amount fields are required otherwise - validated in ClaimService, not via annotations, since which fields are required depends on outcome. */
public record RecordAdjudicationRequest(
        @NotBlank String outcome,
        BigDecimal allowedAmount,
        BigDecimal paidAmount,
        BigDecimal patientResponsibilityAmount,
        String denialReason
) {
}
