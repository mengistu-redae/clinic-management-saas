package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateQcRunRequest(
        @NotBlank String instrumentIdentifier,
        @NotBlank String analyteName,
        @NotBlank String controlMaterialLot,
        @NotNull BigDecimal expectedRangeLow,
        @NotNull BigDecimal expectedRangeHigh,
        @NotBlank String observedValue
) {
}
