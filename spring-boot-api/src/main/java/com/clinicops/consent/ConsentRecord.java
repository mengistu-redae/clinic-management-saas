package com.clinicops.consent;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Genuinely immutable once created - no update/delete endpoint anywhere
 * ({@code ConsentController}) - matches this app's own existing audit
 * -only tables ({@code AppointmentCancellation}, {@code AppointmentReschedule},
 * {@code PhiAccessLog}) and the reference clinical-forms doc's own
 * reasoning ("this matters for legal defensibility"). A patient can
 * accumulate multiple rows of the same {@code consentType} over time (e.g.
 * re-consenting after a policy version change) - {@code ConsentController}'s
 * GET returns the full list, not a singleton, unlike Allergy/Vitals/
 * MedicalHistory.
 *
 * Covers only the reference doc's Form 4 (general treatment) and Form 6
 * (privacy/data-protection) - both are simple acknowledgment-style
 * records. Form 5 (procedure-specific consent - risks explained,
 * alternatives discussed, a specific physician) is a genuinely different
 * shape, deferred to a later refinement, not folded in here.
 *
 * Deliberately lightweight - no signature image captured, even though
 * real file storage (local disk + a Docker volume) became available as of
 * the phase-9 decision. Just an acknowledgment record: who consented, to
 * what, when, witnessed by whom.
 */
@Entity
@Table(name = "consent_records")
@Getter
@Setter
public class ConsentRecord extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** general_treatment, privacy_data. */
    @Column(name = "consent_type", nullable = false)
    private String consentType;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "consent_given", nullable = false)
    private boolean consentGiven = true;

    @Column(name = "witness_name")
    private String witnessName;

    @Column(name = "language_presented")
    private String languagePresented;

    @Column(name = "data_sharing_preferences", columnDefinition = "TEXT")
    private String dataSharingPreferences;

    @Column(name = "signed_at", nullable = false)
    private Instant signedAt = Instant.now();

    @Column(name = "recorded_by")
    private UUID recordedBy;
}
