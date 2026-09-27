package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DispenseRecordRepository extends JpaRepository<DispenseRecord, UUID> {

    List<DispenseRecord> findAllByPrescriptionIdAndTenantId(UUID prescriptionId, UUID tenantId);

    /** Same "compute the running total in a query, not a cached column" shape RefundRepository.sumAmountByPaymentId already established. */
    @Query("SELECT COALESCE(SUM(d.quantityDispensed), 0) FROM DispenseRecord d WHERE d.prescriptionId = :prescriptionId")
    long sumQuantityByPrescriptionId(@Param("prescriptionId") UUID prescriptionId);
}
