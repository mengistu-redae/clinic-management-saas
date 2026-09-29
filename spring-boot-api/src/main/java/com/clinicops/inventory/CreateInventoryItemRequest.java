package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateInventoryItemRequest(
        @NotBlank String name,
        @NotBlank String category,
        String unitOfMeasure,
        BigDecimal unitPrice,
        Integer reorderThreshold
) {
}
