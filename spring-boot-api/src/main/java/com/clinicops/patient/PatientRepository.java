package com.clinicops.patient;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // ---- Phase 45: group-aware accessors, added alongside the tenant-only
    // ones above (never replacing them) - "accessible" means this clinic's
    // own patient OR a patient first seen at a sibling branch in the same
    // clinic group. clinicGroupId is null for a standalone clinic, so the
    // second half of each OR clause simply never matches - zero behavior
    // change for every clinic that hasn't opted into a group. ----

    @Query("""
           SELECT p FROM Patient p
           WHERE p.id = :id
             AND (p.tenantId = :tenantId
               OR (:clinicGroupId IS NOT NULL AND p.clinicGroupId = :clinicGroupId))
           """)
    Optional<Patient> findAccessible(
            @Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("clinicGroupId") UUID clinicGroupId);

    @Query("""
           SELECT p FROM Patient p
           WHERE p.tenantId = :tenantId
              OR (:clinicGroupId IS NOT NULL AND p.clinicGroupId = :clinicGroupId)
           """)
    List<Patient> findAllAccessible(@Param("tenantId") UUID tenantId, @Param("clinicGroupId") UUID clinicGroupId);

    @Query("""
           SELECT p FROM Patient p
           WHERE (p.tenantId = :tenantId
               OR (:clinicGroupId IS NOT NULL AND p.clinicGroupId = :clinicGroupId))
             AND (LOWER(p.firstName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(p.lastName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR p.phone LIKE CONCAT('%', :query, '%')
               OR LOWER(p.nationalId) LIKE LOWER(CONCAT('%', :query, '%')))
           """)
    List<Patient> searchAccessible(
            @Param("tenantId") UUID tenantId, @Param("clinicGroupId") UUID clinicGroupId, @Param("query") String query);

    /** Group-aware portal lookup - finds this same person's record from a sibling branch before PatientProvisioningService falls back to auto-provisioning a new one. */
    @Query("""
           SELECT p FROM Patient p
           WHERE p.appUserId = :appUserId
             AND (p.tenantId = :tenantId
               OR (:clinicGroupId IS NOT NULL AND p.clinicGroupId = :clinicGroupId))
           """)
    Optional<Patient> findAccessibleByAppUserId(
            @Param("tenantId") UUID tenantId, @Param("clinicGroupId") UUID clinicGroupId, @Param("appUserId") UUID appUserId);
}
