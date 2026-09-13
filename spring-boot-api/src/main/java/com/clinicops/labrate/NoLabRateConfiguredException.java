package com.clinicops.labrate;

/** Maps to 400 - a lab order can't be priced without a configured rate, deliberately the opposite of fee_policies' "missing = zero" fallback. */
public class NoLabRateConfiguredException extends RuntimeException {
    public NoLabRateConfiguredException(String message) {
        super(message);
    }
}
