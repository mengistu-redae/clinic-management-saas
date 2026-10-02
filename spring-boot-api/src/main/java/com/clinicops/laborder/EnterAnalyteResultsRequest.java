package com.clinicops.laborder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record EnterAnalyteResultsRequest(@NotEmpty List<@Valid AnalyteResultInput> results) {
}
