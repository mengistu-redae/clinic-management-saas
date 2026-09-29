package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PendingControlledSubstanceDispenseRepository extends JpaRepository<PendingControlledSubstanceDispense, UUID> {

    List<PendingControlledSubstanceDispense> findAllByTenantId(UUID tenantId);

    List<PendingControlledSubstanceDispense> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<PendingControlledSubstanceDispense> findByIdAndTenantId(UUID id, UUID tenantId);
}
