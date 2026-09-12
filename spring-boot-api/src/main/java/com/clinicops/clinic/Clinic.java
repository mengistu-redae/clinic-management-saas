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

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
