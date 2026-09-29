package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;

/** status must be "expired" or "recalled" - checked against VALID_WRITE_OFF_STATUSES in each owning controller. */
public record WriteOffStockBatchRequest(
        @NotBlank String status,
        @NotBlank String reason
) {
}
