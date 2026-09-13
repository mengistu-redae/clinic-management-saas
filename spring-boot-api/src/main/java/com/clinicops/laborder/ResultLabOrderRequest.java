package com.clinicops.laborder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ResultLabOrderRequest(@NotEmpty List<@Valid TestResultInput> results) {
}
