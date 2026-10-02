package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;

public record RejectSpecimenRequest(@NotBlank String reason) {
}
