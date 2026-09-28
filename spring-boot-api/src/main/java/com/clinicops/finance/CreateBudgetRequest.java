package com.clinicops.finance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateBudgetRequest(
        @NotNull UUID accountId,
        @NotNull Integer year,
        @NotNull @Min(1) @Max(12) Integer month,
        @NotNull BigDecimal amount
) {
}
