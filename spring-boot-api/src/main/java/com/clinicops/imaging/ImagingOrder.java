package com.clinicops.imaging;

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
 * One study per order - deliberately not a multi-line-item shape like
 * LabOrder/LabOrderTest (a radiology order is typically one study, unlike
 * a lab panel's many individual tests). Always staff/provider-created -
 * no patient-initiated "requested" flow (lab's own request->confirm
 * -and-order path isn't mirrored here).
 *
 * status: ordered -> scheduled -> in_progress -> completed -> reviewed,
 * or cancelled (pre-study only, i.e. from ordered/scheduled). `imaging_technologist`
 * +clinic_admin own schedule/start/complete (purely mechanical - no
 * clinical content); `provider`+clinic_admin own create/update/cancel and
 * `review` (which is also where findings/impression/criticalFinding get
 * written - one combined action, not a separate "record findings" step,
 * since there's no good role split for narrative clinical content the
 * way lab splits numeric result-entry from clinical review).
 */
@Entity
@Table(name = "imaging_orders")
@Getter
@Setter
public class ImagingOrder extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "ordering_provider_id", nullable = false)
    private UUID orderingProviderId;

    @Column(name = "order_ref", nullable = false, unique = true)
    private String orderRef;

    @Column(name = "clinic_ref")
    private String clinicRef;

    /** xray, ultrasound, ct, mri, other. */
    @Column(nullable = false)
    private String modality;

    @Column(name = "study_type", nullable = false)
    private String studyType;

    /** Snapshotted ImagingStudyRate.studyCode at order-creation time - null if priced ad hoc without a catalog match (not currently possible, kept for shape parity with LabOrderTest.testCode). */
    @Column(name = "study_code")
    private String studyCode;

    @Column(nullable = false)
    private String priority = "routine";

    @Column(nullable = false)
    private String status = "ordered";

    private String notes;

    /** Snapshotted at order-creation time - a later ImagingStudyRate change never re-prices an issued order. */
    @Column(name = "total_cost")
    private BigDecimal totalCost;

    private String findings;

    private String impression;

    @Column(name = "critical_finding", nullable = false)
    private boolean criticalFinding = false;

    @Column(name = "critical_acknowledged_at")
    private Instant criticalAcknowledgedAt;

    @Column(name = "critical_acknowledged_by")
    private UUID criticalAcknowledgedBy;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt = Instant.now();

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;
}
