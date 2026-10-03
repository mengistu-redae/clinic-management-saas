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
 * Shared by both an appointment payment and a lab-order payment - exactly
 * one of appointmentId/labOrderId is set, enforced at the DB level by
 * chk_payments_exactly_one_owner (V5). Recording a genuine cash/card/etc.
 * payment is still a deliberate, separate staff action - creating a booking
 * or lab order never creates or voids one. The one exception is a
 * cancellation/reschedule fee: {@link com.clinicops.appointment.CancellationService}/
 * {@link com.clinicops.appointment.RescheduleService}/
 * {@link com.clinicops.laborder.LabOrderCancellationService} auto-create a
 * {@value #FEE_AUTO_CHARGE_METHOD}-method row for a non-zero computed fee,
 * closing the "nothing auto-creates a payment from a fee" gap - see
 * CLAUDE.md's phase-7 write-up. That row means "this fee was assessed and
 * treated as charged," not "cash/a card was actually collected".
 *
 * Phase 16 - a real (mock-for-now) payment gateway: every payment recorded
 * via AppointmentPaymentController/LabOrderPaymentController now routes
 * through {@link com.clinicops.paymentgateway.PaymentGatewayClient#charge}
 * (see {@link PaymentService}), setting {@code gatewayTransactionId}/
 * {@code gatewayStatus}. The fee_auto_charge shortcut above deliberately
 * bypasses the gateway entirely - both fields stay null for those rows,
 * same as {@code invoiceId} below.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends BaseTenantEntity {

    /** The method value auto-charged cancellation/reschedule fees are recorded under - distinguishes them from a genuine staff-entered cash/card/etc. payment at a glance. */
    public static final String FEE_AUTO_CHARGE_METHOD = "fee_auto_charged";

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "lab_order_id")
    private UUID labOrderId;

    /** Phase 31 - a pharmacy dispense as a third owner type, same exactly-one-owner shape now enforced as a three-way DB CHECK. */
    @Column(name = "dispense_record_id")
    private UUID dispenseRecordId;

    /** Imaging/radiology orders as a fourth owner type (2026-10-04) - the CHECK is now four-way. */
    @Column(name = "imaging_order_id")
    private UUID imagingOrderId;

    @Column(nullable = false)
    private BigDecimal amount;

    /** cash, card, mobile_money, insurance, or FEE_AUTO_CHARGE_METHOD. */
    @Column(nullable = false)
    private String method;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    /** Set only when this payment was recorded against an already-issued invoice - see {@link com.clinicops.invoice.Invoice}. Null for a fee_auto_charge row (no invoice exists yet at that point). */
    @Column(name = "invoice_id")
    private UUID invoiceId;

    /** Only set when this payment was actually routed through {@link com.clinicops.paymentgateway.PaymentGatewayClient#charge} - the fee_auto_charge shortcut leaves this null. */
    @Column(name = "gateway_transaction_id")
    private String gatewayTransactionId;

    /** pending/succeeded/failed/refunded/partially_refunded - the one general lifecycle field, updated by {@link RefundService} regardless of whether the payment was ever gateway-charged. */
    @Column(name = "gateway_status")
    private String gatewayStatus;
}
