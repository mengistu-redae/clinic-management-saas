package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;

public record SendToReferenceLabRequest(
        @NotBlank String referenceLabName,
        String referenceLabOrderNumber,
        Integer expectedTurnaroundDays
) {
}
