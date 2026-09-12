package com.clinicops.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Patient> findAllByTenantId(UUID tenantId);

    Optional<Patient> findByTenantIdAndAppUserId(UUID tenantId, UUID appUserId);

    /** Simple front-desk search - a walk-in is usually found by name or phone. */
    @Query("""
           SELECT p FROM Patient p
           WHERE p.tenantId = :tenantId
             AND (LOWER(p.firstName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phone LIKE CONCAT('%', :query, '%'))
           """)
    List<Patient> search(@Param("tenantId") UUID tenantId, @Param("query") String query);
}
