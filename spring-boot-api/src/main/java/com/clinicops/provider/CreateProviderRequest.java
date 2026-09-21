package com.clinicops.provider;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

/** roomId is optional but, if given, must belong to the caller's own clinic - validated in ProviderController. employmentStatus is allow-listed there too. */
public record CreateProviderRequest(
        @NotBlank String fullName,
        String specialty,
        UUID roomId,
        String licenseNumber,
        LocalDate licenseExpiry,
        String employmentStatus
) {
}
