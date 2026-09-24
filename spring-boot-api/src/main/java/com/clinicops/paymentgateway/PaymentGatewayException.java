package com.clinicops.paymentgateway;

/**
 * The failure is genuinely upstream, at the gateway - maps to 502 in
 * whichever controller can trigger it, same "upstream, not a client error"
 * reasoning KeycloakAdminException already established.
 * {@link MockPaymentGatewayClient} never throws this; a real implementation
 * would, on a declined charge/refund or an unreachable vendor.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
