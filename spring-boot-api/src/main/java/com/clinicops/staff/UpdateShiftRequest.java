package com.clinicops.staff;

import java.time.LocalDate;
import java.time.LocalTime;

/** Partial update - every field optional, only non-null ones are applied. */
public record UpdateShiftRequest(
        LocalDate shiftDate,
        LocalTime startTime,
        LocalTime endTime,
        String notes
) {
}
