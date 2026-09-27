package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicationRepository extends JpaRepository<Medication, UUID> {

    List<Medication> findAllByTenantId(UUID tenantId);

    List<Medication> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Medication> findByIdAndTenantId(UUID id, UUID tenantId);
}
