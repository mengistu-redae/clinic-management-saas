package com.clinicops.appointment;

import java.util.List;

/**
 * Result of "cancel this and the rest of the series" - every still-`booked`
 * occurrence sharing the triggering appointment's own seriesId, each run
 * through the exact same fee/audit/notification path a single cancel uses
 * (see CancellationService.cancelSeries). No conflict/partial-failure shape
 * the way AppointmentSeriesResult's own creation flow needs one - a booked
 * occurrence is always cancellable, there's no external resource (a slot)
 * that could already be taken the way booking a new one has.
 */
public record SeriesCancellationResult(List<Appointment> cancelled) {
}
