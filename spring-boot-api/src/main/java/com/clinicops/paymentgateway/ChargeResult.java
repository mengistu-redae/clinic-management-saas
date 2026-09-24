package com.clinicops.paymentgateway;

/**
 * {@code status} is one of pending/succeeded/failed - a real (future)
 * gateway can legitimately return {@code pending} for a charge only
 * confirmed later via a webhook; {@link MockPaymentGatewayClient} always
 * fast-paths straight to {@code succeeded}, but callers (PaymentService)
 * are written against the general shape so swapping in a real
 * implementation later is a new class, not a redesign.
 */
public record ChargeResult(String transactionId, String status) {
}
