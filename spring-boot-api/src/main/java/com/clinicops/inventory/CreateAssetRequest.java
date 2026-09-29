package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateAssetRequest(
        @NotBlank String name,
        String serialNumber,
        LocalDate purchaseDate,
        BigDecimal purchasePrice,
        LocalDate warrantyExpiry,
        String notes
) {
}
