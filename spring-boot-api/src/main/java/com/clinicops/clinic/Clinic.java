package com.clinicops.clinic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clinics")
@Getter
@Setter
public class Clinic {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "keycloak_org_id", nullable = false, unique = true)
    private String keycloakOrgId;

    @Column(nullable = false)
    private String name;

    /** Collected at onboarding (CreateClinicRequest) - nullable, since every clinic provisioned before V29 has nothing on file. */
    private String domain;

    @Column(nullable = false)
    private String status = "active";

    /**
     * Nullable - opt-in. Set only when a platform_admin links this clinic
     * into a chain/group for cross-branch patient-record sharing (phase 45).
     * Null (the default for every clinic provisioned before this phase) means
     * exactly today's behavior: fully standalone, no sharing with any other
     * clinic.
     */
    @Column(name = "clinic_group_id")
    private UUID clinicGroupId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
