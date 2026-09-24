package com.clinicops.paymentgateway;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Always succeeds, fabricating a gateway transaction id - no real vendor
 * integrated yet (the pinned phase-16 decision). Never throws
 * {@link PaymentGatewayException}. The only {@link PaymentGatewayClient}
 * bean today; a real vendor is a later, separate swap-in.
 */
@Service
public class MockPaymentGatewayClient implements PaymentGatewayClient {

    private static final String SUCCEEDED = "succeeded";

    @Override
    public ChargeResult charge(BigDecimal amount, String method) {
        return new ChargeResult("mock_chg_" + UUID.randomUUID(), SUCCEEDED);
    }

    @Override
    public RefundResult refund(String gatewayTransactionId, BigDecimal amount) {
        return new RefundResult("mock_rfd_" + UUID.randomUUID(), SUCCEEDED);
    }
}
