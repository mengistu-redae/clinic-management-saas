package com.clinicops.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** clinicId is required - a message is clinic-specific by nature, no cross-clinic unified inbox is built here (unlike GET /api/my-lab-orders' own cross-clinic aggregation). */
public record CreatePatientMessageRequest(
        @NotNull UUID clinicId,
        @NotBlank String body
) {
}
