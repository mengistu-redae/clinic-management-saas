package com.clinicops.imaging;

import java.math.BigDecimal;

public record UpdateImagingStudyRateRequest(
        String studyName,
        String modality,
        BigDecimal baseCharge
) {
}
