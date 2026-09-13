package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;

/** One requested test line item, staff-facing (a testCode is known) - see CreateLabOrderRequest/UpdateLabOrderRequest/ConfirmAndOrderRequest. */
public record TestItem(
        @NotBlank String testCode,
        @NotBlank String testName,
        String specimenType,
        String notes
) {
}
