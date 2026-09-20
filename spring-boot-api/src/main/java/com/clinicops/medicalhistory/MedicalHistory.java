package com.clinicops.medicalhistory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One evolving row per patient - {@code patientId} is the primary key
 * itself, same "singleton keyed by its own owner, not a separate id" shape
 * as {@code ClinicSettings} ({@code tenantId} as PK there). No version
 * history: fields get overwritten in place on every update, matching every
 * other non-clinical-note entity in this app (Patient, ClinicSettings) -
 * only Encounter is a candidate for a future versioned/locked treatment
 * (see the phase-12 sketch in CLAUDE.md), and even that was flagged as a
 * deliberate special case, not the default this app reaches for.
 *
 * Distinct from {@code Allergy} (phase 8, structured, its own status
 * lifecycle) and from {@code Encounter.chiefComplaint} (per-visit, not a
 * standing record) - this table covers only what the reference clinical
 * -forms doc's "Medical History Form" calls past_medical_conditions/
 * past_surgeries/current_medications/family_history/social_history.
 * known_allergies is deliberately NOT duplicated here - Allergy is already
 * the real, structured record for that.
 */
@Entity
@Table(name = "medical_history")
@Getter
@Setter
public class MedicalHistory {

    @Id
    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "past_conditions", columnDefinition = "TEXT")
    private String pastConditions;

    @Column(name = "past_surgeries", columnDefinition = "TEXT")
    private String pastSurgeries;

    @Column(name = "current_medications", columnDefinition = "TEXT")
    private String currentMedications;

    @Column(name = "family_history", columnDefinition = "TEXT")
    private String familyHistory;

    @Column(name = "social_history", columnDefinition = "TEXT")
    private String socialHistory;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
