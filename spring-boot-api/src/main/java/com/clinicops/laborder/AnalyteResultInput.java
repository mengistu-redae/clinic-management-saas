package com.clinicops.laborder;

import jakarta.validation.constraints.NotBlank;

/** unit/referenceRangeDisplay/flag are never client-supplied - resolved server-side from the matching AnalyteDefinition, see AnalyteResultService. */
public record AnalyteResultInput(@NotBlank String analyteName, @NotBlank String value) {
}
