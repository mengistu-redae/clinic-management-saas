package com.clinicops.messaging;

import jakarta.validation.constraints.NotBlank;

public record SendStaffMessageRequest(
        @NotBlank String body
) {
}
