package com.clinicops.referral;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Covers both the reference clinical-forms doc's Form 21 (internal,
 * provider-to-provider consult within the same clinic) and Form 12
 * (external, referred out to another clinic/specialist) - one table, not
 * two, matching this app's own precedent for two closely-overlapping
 * shapes ({@code Payment}'s {@code appointmentId}/{@code labOrderId}).
 *
 * Which kind a row is is derived from which fields are populated, not a
 * stored discriminator column:
 * <ul>
 *   <li><b>Internal</b> - {@code receivingProviderId} is set (an
 *       already-provisioned {@code Provider} in this same tenant).</li>
 *   <li><b>External</b> - {@code receivingProviderId} is null and at least
 *       one of {@code externalProviderName}/{@code externalClinicName} is
 *       set instead.</li>
 * </ul>
 * {@code ReferralService.create} enforces this is genuinely one or the
 * other, never both, never neither - see its own javadoc.
 */
@Entity
@Table(name = "referrals")
@Getter
@Setter
public class Referral extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** The visit that triggered this referral, if any - optional, a referral can be made outside any specific encounter. */
    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "referring_provider_id", nullable = false)
    private UUID referringProviderId;

    @Column(name = "receiving_provider_id")
    private UUID receivingProviderId;

    @Column(name = "external_provider_name")
    private String externalProviderName;

    @Column(name = "external_clinic_name")
    private String externalClinicName;

    @Column(name = "referred_to_specialty")
    private String referredToSpecialty;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "clinical_summary", columnDefinition = "TEXT")
    private String clinicalSummary;

    /** routine, urgent. */
    @Column(nullable = false)
    private String priority = "routine";

    /** pending, accepted, scheduled, completed, declined. */
    @Column(nullable = false)
    private String status = "pending";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "completed_at")
    private Instant completedAt;
}
