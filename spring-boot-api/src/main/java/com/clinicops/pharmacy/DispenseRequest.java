package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * The pharmacist explicitly picks stockBatchId - see DispenseRecord's own
 * javadoc for why there's no automatic FEFO allocation.
 *
 * {@code acknowledgeConflict} (phase 27) - same plain-boolean shape as
 * {@code CreateLabOrderRequest.consentAcknowledged} - lets the dispense
 * proceed past a detected allergy/drug-interaction conflict; ignored
 * (never persisted as an override) if no real conflict was actually
 * found.
 */
public record DispenseRequest(
        @NotNull UUID medicationId,
        @NotNull UUID stockBatchId,
        @NotNull @Positive Integer quantity,
        String notes,
        boolean acknowledgeConflict
) {
}
