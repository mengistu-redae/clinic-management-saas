package com.clinicops.laborder;

/** Maps to 400 - a lab order's optional encounterId must belong to the same patient as the order itself. */
public class EncounterPatientMismatchException extends RuntimeException {
    public EncounterPatientMismatchException(String message) {
        super(message);
    }
}
