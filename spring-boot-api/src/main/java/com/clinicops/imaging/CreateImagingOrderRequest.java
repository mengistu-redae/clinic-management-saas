package com.clinicops.imaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateImagingOrderRequest(
        @NotNull UUID patientId,
        @NotNull UUID orderingProviderId,
        @NotBlank String modality,
        @NotBlank String studyType,
        @NotBlank String studyCode,
        String priority,
        String notes
) {
}
