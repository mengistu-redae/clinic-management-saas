package com.clinicops.laborder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * priority defaults to "routine" in the service if null. consentAcknowledged
 * bypasses RestrictedTestException for any testCode matching
 * clinic.lab.restricted-tests.
 */
public record CreateLabOrderRequest(
        @NotNull UUID patientId,
        UUID encounterId,
        @NotNull UUID orderingProviderId,
        String notes,
        String priority,
        @NotEmpty List<@Valid TestItem> tests,
        boolean consentAcknowledged
) {
}
