package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** The pharmacist explicitly picks stockBatchId - see DispenseRecord's own javadoc for why there's no automatic FEFO allocation. */
public record DispenseRequest(
        @NotNull UUID medicationId,
        @NotNull UUID stockBatchId,
        @NotNull @Positive Integer quantity,
        String notes
) {
}
