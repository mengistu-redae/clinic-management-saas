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

    /**
     * Simple front-desk search - a walk-in is usually found by name or phone,
     * but front-desk staff sometimes only have a patient's ID card in hand
     * (nationalId is collected at registration - see Patient.nationalId) -
     * a real, found-live search gap otherwise (2026-10-01 UI audit).
     */
    @Query("""
           SELECT p FROM Patient p
           WHERE p.tenantId = :tenantId
             AND (LOWER(p.firstName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phone LIKE CONCAT('%', :query, '%')
               OR LOWER(p.nationalId) LIKE LOWER(CONCAT('%', :query, '%')))
           """)
    List<Patient> search(@Param("tenantId") UUID tenantId, @Param("query") String query);
}
