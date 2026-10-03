package com.clinicops.insurance;

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
 * Bills one {@link com.clinicops.invoice.Invoice} against one
 * {@link InsurancePolicy} - a plain reference to an already-issued
 * Invoice, not a new Invoice/Payment owner type (no change to either
 * table's own exactly-one-owner CHECK). One invoice can carry more than
 * one claim over its lifetime (e.g. bill the primary payer, then the
 * secondary for whatever's left), so this is a one-to-many, not 1:1.
 *
 * Self-contained lifecycle (see {@link ClaimService}), no real
 * clearinghouse/EDI integration - draft -> submitted -> (paid |
 * partially_paid | denied) -> optionally appealed (from denied, back to
 * an adjudicatable state) -> closed. Every transition is a dedicated
 * action method/endpoint, same convention LabOrderStatusService/
 * CheckInService already established - re-calling a transition already
 * reached is idempotent, calling one out of order throws
 * {@link InvalidClaimStatusException}.
 */
@Entity
@Table(name = "claims")
@Getter
@Setter
public class Claim extends BaseTenantEntity {

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "insurance_policy_id", nullable = false)
    private UUID insurancePolicyId;

    /** Snapshotted directly rather than resolved through invoice -> its owner on every read. */
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "claim_number")
    private String claimNumber;

    /** draft, submitted, paid, partially_paid, denied, appealed, closed. */
    @Column(nullable = false)
    private String status = "draft";

    @Column(name = "billed_amount", nullable = false)
    private BigDecimal billedAmount;

    @Column(name = "allowed_amount")
    private BigDecimal allowedAmount;

    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    @Column(name = "patient_responsibility_amount")
    private BigDecimal patientResponsibilityAmount;

    @Column(name = "denial_reason")
    private String denialReason;

    @Column(name = "appeal_reason")
    private String appealReason;

    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "adjudicated_at")
    private Instant adjudicatedAt;

    @Column(name = "appealed_at")
    private Instant appealedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
