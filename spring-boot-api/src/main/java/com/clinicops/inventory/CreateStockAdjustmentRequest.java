package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateStockAdjustmentRequest(
        @NotNull Integer quantityDelta,
        @NotBlank String reason,
        String notes
) {
}
