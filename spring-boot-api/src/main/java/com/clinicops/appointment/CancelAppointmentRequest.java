package com.clinicops.appointment;

/** {@code reason} is optional - a cancellation is valid with no reason given. */
public record CancelAppointmentRequest(String reason) {
}
