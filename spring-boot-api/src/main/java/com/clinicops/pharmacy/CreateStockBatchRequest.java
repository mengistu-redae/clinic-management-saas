package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record CreateStockBatchRequest(
        String batchNumber,
        @NotNull @Positive Integer quantityReceived,
        LocalDate expiryDate
) {
}
