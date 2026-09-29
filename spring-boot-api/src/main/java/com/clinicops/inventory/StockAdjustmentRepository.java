package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {

    List<StockAdjustment> findAllByStockBatchIdAndTenantId(UUID stockBatchId, UUID tenantId);
}
