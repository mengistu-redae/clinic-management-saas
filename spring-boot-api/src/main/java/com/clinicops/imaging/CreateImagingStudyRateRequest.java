package com.clinicops.imaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateImagingStudyRateRequest(
        @NotBlank String studyCode,
        @NotBlank String studyName,
        @NotBlank String modality,
        @NotNull BigDecimal baseCharge
) {
}
