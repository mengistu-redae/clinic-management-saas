package com.clinicops.platform;

import jakarta.validation.constraints.NotBlank;

public record CreateClinicRequest(
        @NotBlank String name,
        @NotBlank String orgAlias,
        @NotBlank String domain
) {
}
