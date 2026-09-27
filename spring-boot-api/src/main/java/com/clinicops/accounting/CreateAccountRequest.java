package com.clinicops.accounting;

import jakarta.validation.constraints.NotBlank;

public record CreateAccountRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String type
) {
}
