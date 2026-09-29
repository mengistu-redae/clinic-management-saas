package com.clinicops.inventory;

import com.clinicops.analytics.ExpiringBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    List<StockBatch> findAllByMedicationIdAndTenantId(UUID medicationId, UUID tenantId);

    /** Phase 32 - FEFO order as a sort, not an automatic pick; Postgres's own default NULL-ordering on ASC already sorts a no-expiry batch last. */
    List<StockBatch> findAllByMedicationIdAndTenantIdOrderByExpiryDateAsc(UUID medicationId, UUID tenantId);

    List<StockBatch> findAllByInventoryItemIdAndTenantId(UUID inventoryItemId, UUID tenantId);

    Optional<StockBatch> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Phase 34 - unitPrice lives on Medication, not StockBatch, hence the join. Added separately from sumInventoryItemValuation in the controller rather than a UNION, same "plain composition" convention this codebase already keeps. */
    @Query(value = """
            SELECT COALESCE(SUM(sb.quantity_on_hand * m.unit_price), 0)
            FROM stock_batches sb
            JOIN medications m ON m.id = sb.medication_id
            WHERE sb.tenant_id = :tenantId AND sb.status = 'active'
            """, nativeQuery = true)
    BigDecimal sumMedicationValuation(@Param("tenantId") UUID tenantId);

    /** Phase 34 - same shape as sumMedicationValuation, joined to inventory_items instead. */
    @Query(value = """
            SELECT COALESCE(SUM(sb.quantity_on_hand * i.unit_price), 0)
            FROM stock_batches sb
            JOIN inventory_items i ON i.id = sb.inventory_item_id
            WHERE sb.tenant_id = :tenantId AND sb.status = 'active'
            """, nativeQuery = true)
    BigDecimal sumInventoryItemValuation(@Param("tenantId") UUID tenantId);

    /** Phase 34 - a fixed forward-looking cutoff, not the analytics endpoint's own historical `days` window (a deliberately different question - see InventoryAnalyticsController's own javadoc). Resolves the owning catalog row's name via two LEFT JOINs since a batch owns exactly one of medicationId/inventoryItemId. */
    @Query(value = """
            SELECT
                CASE WHEN sb.medication_id IS NOT NULL THEN 'medication' ELSE 'inventory_item' END AS ownerType,
                COALESCE(sb.medication_id, sb.inventory_item_id) AS ownerId,
                COALESCE(m.name, i.name) AS name,
                sb.expiry_date AS expiryDate,
                sb.quantity_on_hand AS quantityOnHand
            FROM stock_batches sb
            LEFT JOIN medications m ON m.id = sb.medication_id
            LEFT JOIN inventory_items i ON i.id = sb.inventory_item_id
            WHERE sb.tenant_id = :tenantId AND sb.status = 'active'
                AND sb.expiry_date IS NOT NULL AND sb.expiry_date <= :cutoff
            ORDER BY sb.expiry_date ASC
            """, nativeQuery = true)
    List<ExpiringBatch> findExpiringSoon(@Param("tenantId") UUID tenantId, @Param("cutoff") LocalDate cutoff);
}
