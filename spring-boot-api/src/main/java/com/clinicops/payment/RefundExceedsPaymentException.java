package com.clinicops.payment;

/** Maps to 400 in PaymentController - a client error, not the gateway's fault. */
public class RefundExceedsPaymentException extends RuntimeException {

    public RefundExceedsPaymentException(String message) {
        super(message);
    }
}
