package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Line items live in their own LabOrderTest table (never flat fields) -
 * every read/write endpoint returns a LabOrderWithTests wrapper, not this
 * entity bare, same "no cross-entity JPA relations" convention as
 * everywhere else in this codebase.
 *
 * status: requested -> ordered -> specimen_collected -> in_transit ->
 * resulted -> reviewed, or cancelled (pre-collection only). "requested" is
 * a patient-initiated order awaiting staff confirm-and-order -
 * orderingProviderId/encounterId are both null until then.
 */
@Entity
@Table(name = "lab_orders")
@Getter
@Setter
public class LabOrder extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "ordering_provider_id")
    private UUID orderingProviderId;

    /** Set only for a patient-initiated request - mirrors Appointment.customerUserId. */
    @Column(name = "customer_user_id")
    private UUID customerUserId;

    private String notes;

    @Column(nullable = false)
    private String priority = "routine";

    @Column(nullable = false)
    private String status = "ordered";

    @Column(name = "order_ref", nullable = false, unique = true)
    private String orderRef;

    @Column(name = "clinic_ref")
    private String clinicRef;

    /** Snapshotted at order-creation (or confirm-and-order) time - a later lab_test_rates change never re-prices an issued order. */
    @Column(name = "total_cost")
    private BigDecimal totalCost;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt = Instant.now();

    @Column(name = "specimen_collected_at")
    private Instant specimenCollectedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "resulted_at")
    private Instant resultedAt;

    @Column(name = "resulted_by")
    private UUID resultedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;
}
