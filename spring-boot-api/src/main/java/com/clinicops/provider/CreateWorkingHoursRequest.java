package com.clinicops.provider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/** dayOfWeek: 0=Sunday..6=Saturday - see SlotGenerator for the exact mapping. */
public record CreateWorkingHoursRequest(
        @NotNull @Min(0) @Max(6) Integer dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
