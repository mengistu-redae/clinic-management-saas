package com.clinicops.appointment;

/** Maps to HTTP 409 in CancellationController/RescheduleController - the appointment was already cancelled. */
public class AppointmentAlreadyCancelledException extends RuntimeException {
    public AppointmentAlreadyCancelledException(String message) {
        super(message);
    }
}
