package com.clinicops.laborder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit row per lab-order cancellation - mirrors AppointmentCancellation
 * exactly (phase 3). Not a BaseTenantEntity - that mandates a created_at
 * column, but this row's own cancelledAt already captures "when this
 * record was created."
 */
@Entity
@Table(name = "lab_order_cancellations")
@Getter
@Setter
public class LabOrderCancellation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "lab_order_id", nullable = false)
    private UUID labOrderId;

    /** Null for a self-cancel with no staff actor - not currently reachable in v1 (patients can't cancel), kept nullable for parity with the appointment shape. */
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    private String reason;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt = Instant.now();
}
