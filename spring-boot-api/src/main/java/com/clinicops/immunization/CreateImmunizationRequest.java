package com.clinicops.immunization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateImmunizationRequest(
        @NotBlank String vaccineName,
        @NotNull LocalDate administeredAt,
        Integer doseNumber,
        String lotNumber,
        String site
) {
}
