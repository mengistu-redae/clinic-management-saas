package com.clinicops.paymentgateway;

/** {@code status} mirrors {@link ChargeResult}'s own pending/succeeded/failed shape. */
public record RefundResult(String refundTransactionId, String status) {
}
