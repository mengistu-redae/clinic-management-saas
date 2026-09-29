package com.clinicops.pharmacy;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Self-maintained, tenant-scoped - this app has no access to a licensed
 * external drug-interaction database, so interaction data is whatever
 * the clinic itself adds (phase 27). Pure config with a well-defined
 * "missing = no known interaction" fallback, same reasoning `FeePolicy`/
 * `LabTestRate` already use for their own hard-delete convention -
 * {@link DrugInteractionPairController} has a real `/delete`, no
 * soft-deactivate. {@code medicationAId}/{@code medicationBId} are fixed
 * at creation (like {@code LabTestRate.testCode}) - correcting which two
 * medications a pair covers means delete+recreate, not an update.
 */
@Entity
@Table(name = "drug_interaction_pairs")
@Getter
@Setter
public class DrugInteractionPair extends BaseTenantEntity {

    @Column(name = "medication_a_id", nullable = false)
    private UUID medicationAId;

    @Column(name = "medication_b_id", nullable = false)
    private UUID medicationBId;

    /** mild, moderate, severe - same convention as Allergy.severity. */
    private String severity;

    @Column(columnDefinition = "TEXT")
    private String description;
}
