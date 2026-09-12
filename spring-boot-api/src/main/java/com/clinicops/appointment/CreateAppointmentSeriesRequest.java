package com.clinicops.appointment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Staff-only (front_desk/provider/clinic_admin) - a patient never self-books a recurring series through the portal. */
public record CreateAppointmentSeriesRequest(
        @NotNull UUID patientId,
        @NotNull UUID providerId,
        @NotNull UUID appointmentTypeId,
        @NotNull Instant firstOccurrenceStart,
        @Min(1) int intervalWeeks,
        @Min(2) int occurrenceCount,
        @NotNull String idempotencyKey
) {
}
