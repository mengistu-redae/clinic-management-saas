package com.clinicops.allergy;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateAllergyRequest(
        @NotBlank String allergen,
        String reactionType,
        String severity,
        LocalDate identifiedAt
) {
}
