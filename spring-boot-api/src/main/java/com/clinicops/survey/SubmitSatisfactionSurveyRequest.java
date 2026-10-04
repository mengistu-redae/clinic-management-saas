package com.clinicops.survey;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** comment is optional - a bare rating with no text is a complete, valid submission. */
public record SubmitSatisfactionSurveyRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        String comment) {
}
