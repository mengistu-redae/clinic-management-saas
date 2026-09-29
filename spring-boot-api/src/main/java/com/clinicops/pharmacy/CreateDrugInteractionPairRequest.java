package com.clinicops.pharmacy;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateDrugInteractionPairRequest(
        @NotNull UUID medicationAId,
        @NotNull UUID medicationBId,
        String severity,
        String description
) {
}
