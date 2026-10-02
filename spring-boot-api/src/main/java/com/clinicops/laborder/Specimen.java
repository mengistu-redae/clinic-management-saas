package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One physical specimen under a LabOrder - derived automatically from the
 * distinct specimenType values among that order's own LabOrderTest rows,
 * never created by hand (see SpecimenService.deriveForOrder). Purely
 * additive alongside LabOrder's own existing status machine, not a
 * replacement for it - mirrors how Prescription stayed untouched when
 * DispenseRecord was added for pharmacy.
 *
 * status: pending_collection -> collected -> in_transit -> received ->
 * processing -> completed, or rejected (from collected/in_transit/received -
 * a specimen can't be rejected before it's even been collected, and once
 * completed it's done). L5 adds one more branch - sent_to_reference_lab,
 * reachable from collected/in_transit/received - for a specimen routed to
 * an outside lab instead of processed in-house; results for one still come
 * back through the existing structured per-analyte path (L2), and a
 * reference-lab specimen completes the same way any other one does.
 */
@Entity
@Table(name = "specimens")
@Getter
@Setter
public class Specimen extends BaseTenantEntity {

    @Column(name = "lab_order_id", nullable = false)
    private UUID labOrderId;

    @Column(name = "specimen_type", nullable = false)
    private String specimenType;

    @Column(nullable = false)
    private String status = "pending_collection";

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "collected_by")
    private UUID collectedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    /** L5 - all null (not applicable) unless this specimen was ever sent to an outside lab. */
    @Column(name = "reference_lab_name")
    private String referenceLabName;

    @Column(name = "reference_lab_order_number")
    private String referenceLabOrderNumber;

    @Column(name = "expected_turnaround_days")
    private Integer expectedTurnaroundDays;

    @Column(name = "sent_to_reference_lab_at")
    private Instant sentToReferenceLabAt;
}
