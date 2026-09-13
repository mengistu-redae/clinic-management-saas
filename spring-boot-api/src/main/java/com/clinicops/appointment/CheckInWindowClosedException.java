package com.clinicops.appointment;

/**
 * Maps to HTTP 409 in CheckInController - the appointment's slot window has
 * already fully elapsed ({@code Instant.now()} is past the slot's end
 * time). Checked live at call time against the slot itself, never against a
 * stored/scheduler-set status - see NoShowScheduler's javadoc for why the
 * two must stay independent.
 */
public class CheckInWindowClosedException extends RuntimeException {
    public CheckInWindowClosedException(String message) {
        super(message);
    }
}
