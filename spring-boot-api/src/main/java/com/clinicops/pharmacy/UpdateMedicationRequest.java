package com.clinicops.pharmacy;

import java.math.BigDecimal;

/** Partial update - only non-null fields are applied. */
public record UpdateMedicationRequest(
        String name,
        String form,
        String unitOfMeasure,
        BigDecimal unitPrice,
        Integer reorderThreshold,
        String status
) {
}
