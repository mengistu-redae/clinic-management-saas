package com.clinicops.laborder;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TestResultInput(
        @NotNull UUID labOrderTestId,
        String value,
        String unit,
        String referenceRange,
        Boolean abnormalFlag
) {
}
