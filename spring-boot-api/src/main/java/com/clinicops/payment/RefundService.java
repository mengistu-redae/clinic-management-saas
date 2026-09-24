package com.clinicops.payment;

import com.clinicops.paymentgateway.PaymentGatewayClient;
import com.clinicops.paymentgateway.RefundResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Full and partial refunds against any payment (electronically gateway
 * -charged or not - a cash refund needs no gateway call, just the audit
 * row and the status update). Cumulative refunded-so-far per payment must
 * never exceed that payment's own amount - a running-sum check, not
 * expressible as a plain DB CHECK, so it's computed here rather than left
 * to a constraint.
 */
@Service
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentGatewayClient gatewayClient;

    public RefundService(PaymentRepository paymentRepository, RefundRepository refundRepository, PaymentGatewayClient gatewayClient) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.gatewayClient = gatewayClient;
    }

    @Transactional
    public Refund refund(UUID tenantId, UUID paymentId, CreateRefundRequest request, UUID refundedBy) {
        Payment payment = paymentRepository.findByIdAndTenantId(paymentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));

        BigDecimal alreadyRefunded = refundRepository.sumAmountByPaymentId(paymentId);
        BigDecimal newTotal = alreadyRefunded.add(request.amount());
        if (newTotal.compareTo(payment.getAmount()) > 0) {
            throw new RefundExceedsPaymentException(
                    "Refund total " + newTotal + " would exceed this payment's own amount " + payment.getAmount());
        }

        // Nothing to refund at the gateway for a payment that was never
        // gateway-charged (a cash payment, or a fee_auto_charge row) -
        // still a real refund, just recorded as an audit row only.
        String gatewayRefundTransactionId = null;
        if (payment.getGatewayTransactionId() != null) {
            RefundResult result = gatewayClient.refund(payment.getGatewayTransactionId(), request.amount());
            gatewayRefundTransactionId = result.refundTransactionId();
        }

        Refund refund = new Refund();
        refund.setTenantId(tenantId);
        refund.setPaymentId(paymentId);
        refund.setAmount(request.amount());
        refund.setReason(request.reason());
        refund.setGatewayRefundTransactionId(gatewayRefundTransactionId);
        refund.setRefundedBy(refundedBy);
        refundRepository.save(refund);

        payment.setGatewayStatus(newTotal.compareTo(payment.getAmount()) == 0 ? "refunded" : "partially_refunded");
        paymentRepository.save(payment);

        return refund;
    }

    public List<Refund> listForPayment(UUID tenantId, UUID paymentId) {
        paymentRepository.findByIdAndTenantId(paymentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));
        return refundRepository.findAllByPaymentIdAndTenantId(paymentId, tenantId);
    }
}
