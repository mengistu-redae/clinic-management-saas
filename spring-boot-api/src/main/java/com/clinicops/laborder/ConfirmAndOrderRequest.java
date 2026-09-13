package com.clinicops.laborder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** tests is optional - null keeps the patient's originally-requested test names as-is (still needing real testCodes assigned via this same field if pricing is to succeed); a non-null list replaces them entirely. */
public record ConfirmAndOrderRequest(
        @NotNull UUID orderingProviderId,
        UUID encounterId,
        List<@Valid TestItem> tests,
        boolean consentAcknowledged
) {
}
