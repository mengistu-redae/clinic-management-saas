package com.clinicops.inventory;

import java.math.BigDecimal;

/** Partial update - only non-null fields are applied. */
public record UpdateInventoryItemRequest(
        String name,
        String category,
        String unitOfMeasure,
        BigDecimal unitPrice,
        Integer reorderThreshold,
        String status
) {
}
