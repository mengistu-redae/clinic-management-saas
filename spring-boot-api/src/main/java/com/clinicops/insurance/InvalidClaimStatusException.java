package com.clinicops.insurance;

/**
 * Maps to 409 - the requested transition isn't legal from the claim's
 * current status. Re-calling a transition already reached is idempotent
 * and does NOT throw this - see ClaimService.
 */
public class InvalidClaimStatusException extends RuntimeException {
    public InvalidClaimStatusException(String message) {
        super(message);
    }
}
