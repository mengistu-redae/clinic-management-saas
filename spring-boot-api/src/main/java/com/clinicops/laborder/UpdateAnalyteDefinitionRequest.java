package com.clinicops.laborder;

import java.math.BigDecimal;

/** testCode/analyteName are fixed at creation, same "read-only after creation" precedent as LabRate.testCode/Account.code. */
public record UpdateAnalyteDefinitionRequest(
        Integer displayOrder,
        String unit,
        BigDecimal normalRangeLow,
        BigDecimal normalRangeHigh,
        String normalRangeText,
        BigDecimal criticalRangeLow,
        BigDecimal criticalRangeHigh
) {
}
