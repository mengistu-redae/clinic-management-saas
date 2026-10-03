package com.clinicops.insurance;

import jakarta.validation.constraints.NotBlank;

public record AppealClaimRequest(
        @NotBlank String appealReason
) {
}
