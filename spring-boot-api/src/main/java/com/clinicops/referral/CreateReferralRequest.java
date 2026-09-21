package com.clinicops.referral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Exactly one of receivingProviderId (internal) or
 * externalProviderName/externalClinicName (external, at least one of the
 * pair) must be given - validated in ReferralService.create, not here,
 * since it's a cross-field rule Bean Validation on a record can't express
 * cleanly.
 */
public record CreateReferralRequest(
        @NotNull UUID patientId,
        UUID encounterId,
        @NotNull UUID referringProviderId,
        UUID receivingProviderId,
        String externalProviderName,
        String externalClinicName,
        String referredToSpecialty,
        @NotBlank String reason,
        String clinicalSummary,
        String priority
) {
}
