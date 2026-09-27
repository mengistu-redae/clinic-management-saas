package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    List<StockBatch> findAllByMedicationIdAndTenantId(UUID medicationId, UUID tenantId);

    Optional<StockBatch> findByIdAndTenantId(UUID id, UUID tenantId);
}
