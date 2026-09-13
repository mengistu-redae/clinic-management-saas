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
 * chk_payments_exactly_one_owner (V5). Recording a payment is a deliberate,
 * separate staff action - creating/cancelling an appointment or lab order
 * never creates or voids one.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends BaseTenantEntity {

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "lab_order_id")
    private UUID labOrderId;

    @Column(nullable = false)
    private BigDecimal amount;

    /** cash, card, mobile_money, insurance. */
    @Column(nullable = false)
    private String method;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "recorded_by")
    private UUID recordedBy;
}
