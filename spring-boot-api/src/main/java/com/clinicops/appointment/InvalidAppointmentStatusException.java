package com.clinicops.appointment;

/**
 * Maps to HTTP 409 in CheckInController - the requested check-in transition
 * isn't legal from the appointment's current status (an out-of-order call,
 * e.g. {@code room} before {@code check-in}). Re-calling a transition
 * already reached is idempotent and does NOT throw this - see
 * CheckInService.
 */
public class InvalidAppointmentStatusException extends RuntimeException {
    public InvalidAppointmentStatusException(String message) {
        super(message);
    }
}
