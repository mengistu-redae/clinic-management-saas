package com.clinicops.imaging;

import java.util.UUID;

/** Partial update, only while status = "ordered" - mirrors UpdateLabOrderRequest's own gate. */
public record UpdateImagingOrderRequest(
        UUID orderingProviderId,
        String modality,
        String studyType,
        String studyCode,
        String priority,
        String notes
) {
}
