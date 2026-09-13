package com.clinicops.encounter;

import jakarta.validation.constraints.NotBlank;

/** dosage/instructions are optional, matching the nullable prescriptions columns. */
public record PrescriptionInput(@NotBlank String medicationName, String dosage, String instructions) {
}
