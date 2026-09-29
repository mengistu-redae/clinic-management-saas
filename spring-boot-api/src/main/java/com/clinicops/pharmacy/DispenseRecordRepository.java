package com.clinicops.pharmacy;

import com.clinicops.analytics.DailyCount;
import com.clinicops.analytics.MedicationDispenseCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispenseRecordRepository extends JpaRepository<DispenseRecord, UUID> {

    List<DispenseRecord> findAllByPrescriptionIdAndTenantId(UUID prescriptionId, UUID tenantId);

    /** Phase 31 - a genuine gap: no controller ever needed to address one dispense record directly until the billing endpoints did. */
    Optional<DispenseRecord> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Same "compute the running total in a query, not a cached column" shape RefundRepository.sumAmountByPaymentId already established. */
    @Query("SELECT COALESCE(SUM(d.quantityDispensed), 0) FROM DispenseRecord d WHERE d.prescriptionId = :prescriptionId")
    long sumQuantityByPrescriptionId(@Param("prescriptionId") UUID prescriptionId);

    /** Phase 34 - units dispensed per day, not a row count, same "no own dispensedAt, key off inherited createdAt" reasoning as every other day-bucketed query in this app. */
    @Query(value = """
            SELECT CAST(created_at AS date) AS day, COALESCE(SUM(quantity_dispensed), 0) AS total
            FROM dispense_records
            WHERE tenant_id = :tenantId AND created_at >= :since
            GROUP BY CAST(created_at AS date)
            ORDER BY day
            """, nativeQuery = true)
    List<DailyCount> findDailyDispenseVolume(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    /** Phase 34 - embeds the medication name directly, same reasoning MedicationDispenseCount's own javadoc gives. */
    @Query(value = """
            SELECT d.medication_id AS medicationId, m.name AS medicationName, COALESCE(SUM(d.quantity_dispensed), 0) AS total
            FROM dispense_records d
            JOIN medications m ON m.id = d.medication_id
            WHERE d.tenant_id = :tenantId AND d.created_at >= :since
            GROUP BY d.medication_id, m.name
            ORDER BY total DESC
            """, nativeQuery = true)
    List<MedicationDispenseCount> countByMedicationSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}
