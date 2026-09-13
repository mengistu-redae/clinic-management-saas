package com.clinicops.labrate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateLabTestRateRequest(
        @NotBlank String testCode,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal baseCharge,
        @DecimalMin(value = "0", inclusive = true) BigDecimal collectionFee
) {
}
