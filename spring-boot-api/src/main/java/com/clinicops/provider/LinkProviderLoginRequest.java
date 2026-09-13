package com.clinicops.provider;

import jakarta.validation.constraints.NotBlank;

public record LinkProviderLoginRequest(@NotBlank String email) {
}
