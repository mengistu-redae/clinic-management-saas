package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Same fields as {@link DispenseRequest} - kept as its own record rather
 * than reused directly since the two flows may reasonably diverge later
 * (matches {@code CreateDrugInteractionPairRequest} being kept separate
 * from other request shapes for the same reason).
 */
public record RequestControlledSubstanceDispenseRequest(
        @NotNull UUID medicationId,
        @NotNull UUID stockBatchId,
        @NotNull @Positive Integer quantity,
        String notes,
        boolean acknowledgeConflict
) {
}
