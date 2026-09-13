package com.clinicops.appointment;

/**
 * Maps to HTTP 409 in CheckInController - a presented ID number doesn't
 * match the linked patient's ID on file. A patient with NO id on file is
 * deliberately NOT a mismatch (decided in plan mode) - this is thrown only
 * for a genuine value-vs-value mismatch.
 */
public class IdentityMismatchException extends RuntimeException {
    public IdentityMismatchException(String message) {
        super(message);
    }
}
