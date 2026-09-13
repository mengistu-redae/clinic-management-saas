package com.clinicops.laborder;

/** Maps to 400 - on update, `tests: null` means "don't touch", but an explicit empty list is rejected rather than silently clearing every test. */
public class InvalidLabOrderTestsException extends RuntimeException {
    public InvalidLabOrderTestsException(String message) {
        super(message);
    }
}
