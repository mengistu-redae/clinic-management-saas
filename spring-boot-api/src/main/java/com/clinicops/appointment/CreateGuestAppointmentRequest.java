package com.clinicops.appointment;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * No account, no session, no JWT at all - a visitor books with contact info
 * instead of an identity, same pattern as the reference project's
 * CreateGuestBookingRequest. contactEmail is optional and never persisted
 * (see AppointmentWriter) - it only flows through as the transient
 * notification recipient; contactPhone is required, since it's also the
 * second factor for the public tracking lookup later.
 */
public record CreateGuestAppointmentRequest(
        @NotNull UUID slotId,
        @NotNull UUID providerId,
        @NotNull UUID appointmentTypeId,
        @NotBlank String contactName,
        @NotBlank String contactPhone,
        @Email String contactEmail,
        @NotNull String idempotencyKey
) {
}
