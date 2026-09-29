package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    List<Supplier> findAllByTenantId(UUID tenantId);

    List<Supplier> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Supplier> findByIdAndTenantId(UUID id, UUID tenantId);
}
