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
 * A lightweight refill-request workflow (phase 33) - reuses
 * {@code LabOrder}'s own phase-7 patient-request -> staff-confirm shape
 * rather than inventing a new pattern. {@code requestedBy} is always set
 * - only a patient can create one. Approving does not itself dispense
 * anything - the actual fulfillment still happens through the existing
 * {@code POST /api/prescriptions/{id}/dispense} flow; this entity is
 * purely the communication/acknowledgment step, matching the sketch's
 * own "lightweight" framing.
 */
@Entity
@Table(name = "prescription_refill_requests")
@Getter
@Setter
public class PrescriptionRefillRequest extends BaseTenantEntity {

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    private String notes;

    /** requested, approved, denied. */
    @Column(nullable = false)
    private String status = "requested";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_notes")
    private String reviewNotes;
}
