package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    List<InventoryItem> findAllByTenantId(UUID tenantId);

    List<InventoryItem> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<InventoryItem> findByIdAndTenantId(UUID id, UUID tenantId);
}
