package com.clinicops.appointment;

/** Maps to HTTP 409 in AppointmentController - the slot was already locked or booked by another request. */
public class SlotConflictException extends RuntimeException {
    public SlotConflictException(String message) {
        super(message);
    }
}
