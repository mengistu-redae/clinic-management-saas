package com.clinicops.encounter;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A single medication line item on an encounter's "simple prescription
 * list" (kickoff spec). Extended in phase 11 with route/frequency/duration/
 * quantityDispensed/refillsAllowed/status (the reference clinical-forms
 * doc's own Form 9 fields) - all still optional except {@code status}
 * (defaults to "active"), matching the original minimal-viable columns'
 * own nullability. Prescriptions are still always replaced as a whole list
 * (see EncounterService.replacePrescriptions), never individually
 * patched - {@code status} describes this specific prescription instance
 * (e.g. a walk-in's antibiotic course later marked "completed" on a
 * resend of the list), not a cross-encounter ongoing-medication concept;
 * this table stays encounter-scoped, not patient-scoped, so it can't
 * represent "a medication from 3 visits ago" - deliberately not a bigger
 * architecture change than this phase warrants.
 */
@Entity
@Table(name = "prescriptions")
@Getter
@Setter
public class Prescription extends BaseTenantEntity {

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "medication_name", nullable = false)
    private String medicationName;

    private String dosage;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    /** oral, iv, im, subcutaneous, topical, inhaled, rectal, sublingual, other. */
    private String route;

    /** Free text, e.g. "twice daily". */
    private String frequency;

    /** Free text, e.g. "7 days". */
    private String duration;

    @Column(name = "quantity_dispensed")
    private Integer quantityDispensed;

    @Column(name = "refills_allowed")
    private Integer refillsAllowed;

    /** active, completed, discontinued. */
    @Column(nullable = false)
    private String status = "active";
}
