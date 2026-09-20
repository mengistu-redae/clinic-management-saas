package com.clinicops.consent;

import jakarta.validation.constraints.NotBlank;

/** consentGiven null defaults to true - a consent record being created almost always means consent WAS given, but staff can explicitly record a decline by passing false. */
public record CreateConsentRecordRequest(
        @NotBlank String consentType,
        @NotBlank String policyVersion,
        Boolean consentGiven,
        String witnessName,
        String languagePresented,
        String dataSharingPreferences
) {
}
