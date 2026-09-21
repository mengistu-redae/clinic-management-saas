package com.clinicops.provider;

import java.time.LocalDate;
import java.util.UUID;

/** Partial update - every field optional, only non-null ones are applied. roomId/status/employmentStatus are validated in ProviderController. */
public record UpdateProviderRequest(
        String fullName,
        String specialty,
        UUID roomId,
        String status,
        String licenseNumber,
        LocalDate licenseExpiry,
        String employmentStatus
) {
}
