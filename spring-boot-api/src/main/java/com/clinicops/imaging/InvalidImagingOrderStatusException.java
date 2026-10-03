package com.clinicops.imaging;

/**
 * Maps to 409 - mirrors InvalidLabOrderStatusException exactly. Re-calling
 * a transition already reached is idempotent and does NOT throw this -
 * see ImagingOrderStatusService.
 */
public class InvalidImagingOrderStatusException extends RuntimeException {
    public InvalidImagingOrderStatusException(String message) {
        super(message);
    }
}
