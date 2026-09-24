package com.clinicops.paymentgateway;

import java.math.BigDecimal;

/**
 * The one seam a real payment vendor integration slots into later -
 * mirrors com.clinicops.notification.NotificationSender's own
 * "interface + swap the bean later" shape. {@link MockPaymentGatewayClient}
 * is the only implementation for now (mock-only, per the pinned phase-16
 * decision); wiring a real vendor is a separate later decision once real
 * credentials exist.
 */
public interface PaymentGatewayClient {

    ChargeResult charge(BigDecimal amount, String method);

    RefundResult refund(String gatewayTransactionId, BigDecimal amount);
}
