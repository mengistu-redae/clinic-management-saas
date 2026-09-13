package com.clinicops.provider;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** roomId is optional but, if given, must belong to the caller's own clinic - validated in ProviderController. */
public record CreateProviderRequest(@NotBlank String fullName, String specialty, UUID roomId) {
}
