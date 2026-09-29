package com.clinicops.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreatePurchaseOrderRequest(
        @NotNull UUID supplierId,
        String notes,
        @NotEmpty @Valid List<PurchaseOrderLineRequest> lines
) {
    public record PurchaseOrderLineRequest(
            UUID medicationId,
            UUID inventoryItemId,
            @NotNull @Positive Integer quantityOrdered,
            BigDecimal unitCost
    ) {
    }
}
