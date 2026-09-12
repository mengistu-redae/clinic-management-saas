package com.clinicops.appointment;

/** Maps to HTTP 403 in AppointmentController - a front_desk caller tried to book a slot belonging to a different clinic. */
public class TenantMismatchException extends RuntimeException {
    public TenantMismatchException(String message) {
        super(message);
    }
}
