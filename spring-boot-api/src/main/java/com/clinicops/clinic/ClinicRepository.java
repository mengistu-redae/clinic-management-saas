package com.clinicops.clinic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClinicRepository extends JpaRepository<Clinic, UUID> {

    /** Maps a Keycloak Organization alias (from the JWT) to our internal tenant id. */
    Optional<Clinic> findByKeycloakOrgId(String keycloakOrgId);

    // ---- platform_admin (cross-tenant, phase 6): ----

    long countByStatus(String status);

    List<Clinic> findAllByStatusOrderByName(String status);
}
