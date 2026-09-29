package com.clinicops.pharmacy;

/**
 * Thrown when a dispense would conflict with the patient's own recorded
 * allergies or a configured {@link DrugInteractionPair}, and the request
 * didn't carry {@code acknowledgeConflict=true}. Maps to 409 in
 * {@link DispenseController} - a real precondition blocking this
 * dispense, same reasoning {@link InsufficientStockException} already
 * uses 409 for, not a malformed-input 400.
 */
public class ClinicalSafetyConflictException extends RuntimeException {
    public ClinicalSafetyConflictException(String message) {
        super(message);
    }
}
