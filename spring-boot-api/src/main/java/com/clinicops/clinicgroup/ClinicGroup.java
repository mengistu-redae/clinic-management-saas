package com.clinicops.clinicgroup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Groups several {@code Clinic} rows (branches) of the same chain so their
 * patients' identity and clinical history can be shared across branches -
 * see CLAUDE.md's "Phase 45" write-up. Deliberately not named "Organization"
 * - that word already means something else in this codebase (one Keycloak
 * Organization per individual Clinic, used for login/membership since phase
 * 6). Clinic itself stays the operational tenant for everything else
 * (scheduling/inventory/finance/staff config) - this entity exists purely to
 * carry the opt-in patient-sharing relationship.
 */
@Entity
@Table(name = "clinic_groups")
@Getter
@Setter
public class ClinicGroup {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
