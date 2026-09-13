package com.clinicops.appointment;

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
 * Audit row per cancellation - mirrors the reference project's
 * `cancellations` table. Not a {@code BaseTenantEntity} - that mandates a
 * {@code created_at} column, but this row's own {@code cancelledAt} already
 * captures "when this record was created" (a cancellation audit row is
 * created at the moment of cancellation); a separate created_at would be
 * redundant. Same reasoning as {@link AppointmentSeries}.
 */
@Entity
@Table(name = "appointment_cancellations")
@Getter
@Setter
public class AppointmentCancellation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "appointment_id", nullable = false)
    private UUID appointmentId;

    /** Null for a patient self-cancel - no staff actor to record. */
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    private String reason;

    @Column(name = "fee_amount", nullable = false)
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "cancelled_at", nullable = false)
    private Instant cancelledAt = Instant.now();
}
