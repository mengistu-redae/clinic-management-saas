package com.clinicops.payment;

/** Phase 46 - mirrors RefundExceedsPaymentException's own shape: a running-sum check that can't be a plain DB CHECK, so it's computed in PaymentService rather than left to a constraint. */
public class PaymentExceedsInvoiceBalanceException extends RuntimeException {
    public PaymentExceedsInvoiceBalanceException(String message) {
        super(message);
    }
}
