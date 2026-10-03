package com.clinicops.imaging;

/** Maps to 400 - mirrors NoLabRateConfiguredException exactly; a missing rate blocks order creation entirely, the opposite of fee_policies' own "missing = zero" fallback. */
public class NoImagingRateConfiguredException extends RuntimeException {
    public NoImagingRateConfiguredException(String message) {
        super(message);
    }
}
