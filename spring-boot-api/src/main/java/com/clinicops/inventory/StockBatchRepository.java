package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    List<StockBatch> findAllByMedicationIdAndTenantId(UUID medicationId, UUID tenantId);

    /** Phase 32 - FEFO order as a sort, not an automatic pick; Postgres's own default NULL-ordering on ASC already sorts a no-expiry batch last. */
    List<StockBatch> findAllByMedicationIdAndTenantIdOrderByExpiryDateAsc(UUID medicationId, UUID tenantId);

    List<StockBatch> findAllByInventoryItemIdAndTenantId(UUID inventoryItemId, UUID tenantId);

    Optional<StockBatch> findByIdAndTenantId(UUID id, UUID tenantId);
}
