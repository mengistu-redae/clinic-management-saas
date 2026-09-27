package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotBlank;

/** status must be "expired" or "recalled" - checked in StockBatchController against VALID_WRITE_OFF_STATUSES. */
public record WriteOffStockBatchRequest(
        @NotBlank String status,
        @NotBlank String reason
) {
}
