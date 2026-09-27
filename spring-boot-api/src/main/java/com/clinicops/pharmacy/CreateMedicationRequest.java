package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record CreateMedicationRequest(
        @NotBlank String name,
        String form,
        String unitOfMeasure,
        BigDecimal unitPrice,
        Integer reorderThreshold
) {
}
