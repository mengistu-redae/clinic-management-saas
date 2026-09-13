package com.clinicops.appointment;

/**
 * Maps to HTTP 409 in RescheduleController - fewer than
 * {@code clinicops.appointment.reschedule.min-notice-hours} remain before
 * the current slot's start time. The caller is expected to fall back to
 * cancellation instead; this endpoint doesn't do it for them.
 */
public class TooLateToRescheduleException extends RuntimeException {
    public TooLateToRescheduleException(String message) {
        super(message);
    }
}
