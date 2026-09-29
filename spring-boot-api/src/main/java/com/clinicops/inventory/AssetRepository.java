package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findAllByTenantId(UUID tenantId);

    List<Asset> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Asset> findByIdAndTenantId(UUID id, UUID tenantId);
}
