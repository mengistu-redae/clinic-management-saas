package com.clinicops.finance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateEmployeeRequest(
        @NotBlank String email,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal salaryAmount
) {
}
