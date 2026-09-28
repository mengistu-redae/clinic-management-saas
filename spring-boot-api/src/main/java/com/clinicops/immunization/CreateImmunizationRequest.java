package com.clinicops.immunization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** appointmentId (phase 26) optionally ties this dose to the visit it was given at - validated tenant-scoped, not required. */
public record CreateImmunizationRequest(
        @NotBlank String vaccineName,
        @NotNull LocalDate administeredAt,
        Integer doseNumber,
        String lotNumber,
        String site,
        UUID appointmentId
) {
}
