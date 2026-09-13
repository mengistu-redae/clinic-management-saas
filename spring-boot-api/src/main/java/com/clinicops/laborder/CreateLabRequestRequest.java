package com.clinicops.laborder;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** A patient's own request - no pricing, no encounter, tests are freeform names only (staff assigns real testCodes at confirm-and-order). */
public record CreateLabRequestRequest(
        @NotNull UUID clinicId,
        @NotEmpty List<String> testNames,
        String notes
) {
}
