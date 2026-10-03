package com.clinicops.insurance;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateClaimRequest(
        @NotNull UUID insurancePolicyId,
        String notes
) {
}
