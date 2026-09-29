package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findAllByTenantId(UUID tenantId);

    Optional<PurchaseOrder> findByIdAndTenantId(UUID id, UUID tenantId);
}
