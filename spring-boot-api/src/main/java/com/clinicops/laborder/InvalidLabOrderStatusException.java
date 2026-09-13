package com.clinicops.laborder;

/**
 * Maps to 409 - the requested transition isn't legal from the order's
 * current status (an out-of-order call), or a clinical field was edited
 * after status left "ordered". Re-calling a transition already reached is
 * idempotent and does NOT throw this - see LabOrderStatusService.
 */
public class InvalidLabOrderStatusException extends RuntimeException {
    public InvalidLabOrderStatusException(String message) {
        super(message);
    }
}
