package com.clinicops.pharmacy;

/** Maps to HTTP 409 in DispenseController - the picked stock batch doesn't have enough quantityOnHand for this dispense. */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
