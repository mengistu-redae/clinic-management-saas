package com.clinicops.laborder;

/** Maps to 400 unless the request carries consentAcknowledged=true - a testCode matched clinic.lab.restricted-tests. */
public class RestrictedTestException extends RuntimeException {
    public RestrictedTestException(String message) {
        super(message);
    }
}
