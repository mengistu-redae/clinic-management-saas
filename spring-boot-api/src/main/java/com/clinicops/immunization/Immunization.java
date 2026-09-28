package com.clinicops.immunization;

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
 * Patient-level, not encounter-level - a vaccination isn't tied to one
 * specific visit, and accumulates as a list over time the same shape as
 * Allergy. Unlike Allergy, no status field - each row is a discrete
 * historical fact (a dose given on a date), not an evolving condition with
 * something to resolve/unconfirm. No delete endpoint (ImmunizationController) -
 * correcting a mistake means a partial update to the non-identity fields
 * only; vaccineName/administeredAt/patientId are fixed at creation.
 */
@Entity
@Table(name = "immunizations")
@Getter
@Setter
public class Immunization extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "vaccine_name", nullable = false)
    private String vaccineName;

    @Column(name = "administered_at", nullable = false)
    private LocalDate administeredAt;

    @Column(name = "dose_number")
    private Integer doseNumber;

    @Column(name = "lot_number")
    private String lotNumber;

    private String site;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    /** Not on BaseTenantEntity (only createdAt is) - set on every update, same convention as Allergy.updatedAt. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
