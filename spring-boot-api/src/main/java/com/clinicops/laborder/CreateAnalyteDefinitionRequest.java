package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateAnalyteDefinitionRequest(
        @NotBlank String testCode,
        @NotBlank String analyteName,
        Integer displayOrder,
        String unit,
        BigDecimal normalRangeLow,
        BigDecimal normalRangeHigh,
        String normalRangeText,
        BigDecimal criticalRangeLow,
        BigDecimal criticalRangeHigh
) {
}
