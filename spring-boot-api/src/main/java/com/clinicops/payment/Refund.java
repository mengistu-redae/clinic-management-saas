package com.clinicops.payment;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Genuinely immutable once created - no update/delete endpoint anywhere,
 * same "audit-only" shape as AppointmentCancellation/ConsentRecord/
 * PhiAccessLog. A payment can accumulate multiple partial refunds over
 * time; {@link RefundService} is the one place their cumulative total is
 * checked against the payment's own amount.
 */
@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund extends BaseTenantEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false)
    private BigDecimal amount;

    private String reason;

    /** Null when the underlying payment was never gateway-charged (e.g. a cash payment) - nothing to refund at the gateway in that case, only this audit row. */
    @Column(name = "gateway_refund_transaction_id")
    private String gatewayRefundTransactionId;

    @Column(name = "refunded_by")
    private UUID refundedBy;
}
