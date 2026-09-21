package com.clinicops.encounter;

import jakarta.validation.constraints.NotBlank;

public record AddendumInput(@NotBlank String text) {
}
