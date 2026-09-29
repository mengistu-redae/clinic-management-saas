package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DrugInteractionPairRepository extends JpaRepository<DrugInteractionPair, UUID> {

    List<DrugInteractionPair> findAllByTenantId(UUID tenantId);

    Optional<DrugInteractionPair> findByIdAndTenantId(UUID id, UUID tenantId);
}
