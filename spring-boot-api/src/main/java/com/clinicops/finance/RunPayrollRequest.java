package com.clinicops.finance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RunPayrollRequest(
        @NotNull Integer year,
        @NotNull @Min(1) @Max(12) Integer month
) {
}
