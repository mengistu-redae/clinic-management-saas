package com.clinicops.appointment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Moves only the slot (and, optionally, the provider - within the same
 * clinic; a different clinic is a new appointment, not a reschedule).
 * Patient/contact fields are never editable here - the "immutability
 * principle": everything about who the appointment is for carries over
 * unchanged.
 */
public record RescheduleAppointmentRequest(
        @NotNull UUID newSlotId,
        @NotNull UUID newProviderId
) {
}
