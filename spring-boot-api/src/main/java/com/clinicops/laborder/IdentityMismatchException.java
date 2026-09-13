package com.clinicops.laborder;

/**
 * Maps to 409 on collect-specimen. Deliberately its own class, not a reuse
 * of com.clinicops.appointment.IdentityMismatchException, even though the
 * check is shaped identically - and deliberately the OPPOSITE semantics
 * from that one: here, no ID on file also throws (per the kickoff spec's
 * explicit instruction for this module), where check-in's own version
 * allows a presented ID through when nothing is on file to compare
 * against. Two different clinical-workflow calls, kept as two classes on
 * purpose so a future change to one never silently changes the other.
 */
public class IdentityMismatchException extends RuntimeException {
    public IdentityMismatchException(String message) {
        super(message);
    }
}
