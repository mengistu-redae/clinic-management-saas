package com.clinicops.patient;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreatePatientRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        LocalDate dateOfBirth,
        String phone,
        String email,
        String nationalId,
        String insuranceMemberId
) {
}
