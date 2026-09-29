package com.clinicops.pharmacy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The dual-sign-off workflow entity for a controlled-substance dispense
 * (phase 28) - deliberately a **new, separate, mutable** entity rather
 * than a mid-flight state on {@link DispenseRecord} itself, since that
 * entity's own javadoc commits it to being genuinely append-only. Once a
 * *different* pharmacist/clinic_admin co-signs, DispenseService creates
 * the real, permanent DispenseRecord and this row just records how it
 * got there ({@code dispenseRecordId}).
 */
@Entity
@Table(name = "pending_controlled_substance_dispenses")
@Getter
@Setter
public class PendingControlledSubstanceDispense extends BaseTenantEntity {

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "medication_id", nullable = false)
    private UUID medicationId;

    @Column(name = "stock_batch_id", nullable = false)
    private UUID stockBatchId;

    @Column(nullable = false)
    private int quantity;

    private String notes;

    @Column(name = "safety_override_acknowledged", nullable = false)
    private boolean safetyOverrideAcknowledged = false;

    /** pending, cosigned, rejected. */
    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "co_signed_by")
    private UUID coSignedBy;

    @Column(name = "co_signed_at")
    private Instant coSignedAt;

    /** Set once cosigned - the permanent record this request produced. */
    @Column(name = "dispense_record_id")
    private UUID dispenseRecordId;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;
}
