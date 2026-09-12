package com.clinicops.appointment;

import java.time.Instant;
import java.util.List;

/**
 * Deliberately allows partial success: a gap in a series is more useful
 * than an all-or-nothing failure staff would then have to manually
 * reconstruct. AppointmentSeriesController maps this to 201 if at least one
 * occurrence was created, 409 if the very first occurrence's slot was
 * unavailable (nothing created at all).
 */
public record AppointmentSeriesResult(
        AppointmentSeries series,
        List<Appointment> created,
        List<Conflict> conflicts
) {
    public record Conflict(int occurrenceIndex, Instant attemptedStartTime, String reason) {
    }
}
