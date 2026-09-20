package com.clinicops.allergy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient-level, not encounter-level - part of the patient's standing
 * health record, not tied to one visit. No delete endpoint exists
 * (AllergyController) - a mistaken entry gets corrected by adding a new
 * row and marking the old one resolved/unconfirmed via {@link #status},
 * never erased outright, matching this app's own precedent for
 * safety/history data (providers/rooms soft-deactivate rather than delete).
 */
@Entity
@Table(name = "allergies")
@Getter
@Setter
public class Allergy extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private String allergen;

    @Column(name = "reaction_type")
    private String reactionType;

    /** mild, moderate, severe. */
    @Column(nullable = false)
    private String severity = "moderate";

    /** active, resolved, unconfirmed. */
    @Column(nullable = false)
    private String status = "active";

    @Column(name = "identified_at")
    private LocalDate identifiedAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    /** Not on BaseTenantEntity (only createdAt is) - set on every update, same convention as Encounter.updatedAt. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
