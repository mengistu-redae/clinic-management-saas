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
 * treated as charged," not "cash/a card was actually collected" - this app
 * still has no real payment gateway, so there's no stronger claim to make.
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

    @Column(nullable = false)
    private BigDecimal amount;

    /** cash, card, mobile_money, insurance, or FEE_AUTO_CHARGE_METHOD. */
    @Column(nullable = false)
    private String method;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "recorded_by")
    private UUID recordedBy;
}
