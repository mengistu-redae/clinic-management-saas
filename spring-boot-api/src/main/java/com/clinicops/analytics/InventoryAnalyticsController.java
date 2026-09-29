package com.clinicops.analytics;

import com.clinicops.inventory.InventoryItem;
import com.clinicops.inventory.InventoryItemRepository;
import com.clinicops.inventory.ReorderAlert;
import com.clinicops.inventory.StockBatch;
import com.clinicops.inventory.StockBatchRepository;
import com.clinicops.inventory.AssetRepository;
import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 34 - the clinic_admin/front_desk-facing half of the "unified
 * reporting & analytics" module, mirroring ClinicAnalyticsController's own
 * shape (no dedicated service bean, plain read composition). clinic_admin+
 * front_desk, matching InventoryItemController's own gate.
 *
 * Unlike PharmacyAnalyticsController, the `days` parameter here currently
 * governs nothing - every field is a current-state snapshot (total
 * valuation, low stock, expiring-soon, asset status counts), not a
 * historical series. Kept for shape parity with the sibling endpoint and
 * for possible future use, not silently dead - see each field's own
 * javadoc for why it isn't windowed.
 *
 * lowStock duplicates InventoryItemController.reorderAlerts()'s own
 * aggregation loop rather than depending on that controller as a
 * collaborator - a deliberate choice matching this codebase's existing
 * "plain composition across repositories, no cross-controller coupling"
 * convention (the same reasoning ClinicAnalyticsController's own javadoc
 * gives for not introducing a service bean here either).
 */
@RestController
public class InventoryAnalyticsController {

    private static final int EXPIRING_SOON_DAYS = 30;

    private final MedicationRepository medicationRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final AssetRepository assetRepository;

    public InventoryAnalyticsController(
            MedicationRepository medicationRepository,
            InventoryItemRepository inventoryItemRepository,
            StockBatchRepository stockBatchRepository,
            AssetRepository assetRepository) {
        this.medicationRepository = medicationRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.assetRepository = assetRepository;
    }

    @GetMapping("/api/inventory/analytics")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public InventoryAnalyticsSummary analytics(@RequestParam(name = "days", required = false) Integer days) {
        UUID tenantId = TenantContext.require();
        BigDecimal totalValuation = stockBatchRepository.sumMedicationValuation(tenantId)
                .add(stockBatchRepository.sumInventoryItemValuation(tenantId));

        return new InventoryAnalyticsSummary(
                totalValuation,
                lowStock(tenantId),
                stockBatchRepository.findExpiringSoon(tenantId, LocalDate.now().plusDays(EXPIRING_SOON_DAYS)),
                assetRepository.countByStatus(tenantId));
    }

    /** Same aggregation InventoryItemController.reorderAlerts() already performs - see this class's own javadoc for why it's duplicated here rather than shared. */
    private List<ReorderAlert> lowStock(UUID tenantId) {
        List<ReorderAlert> alerts = new ArrayList<>();
        for (Medication medication : medicationRepository.findAllByTenantIdAndStatus(tenantId, "active")) {
            if (medication.getReorderThreshold() <= 0) {
                continue;
            }
            int onHand = stockBatchRepository.findAllByMedicationIdAndTenantId(medication.getId(), tenantId).stream()
                    .filter(b -> "active".equals(b.getStatus()))
                    .mapToInt(StockBatch::getQuantityOnHand)
                    .sum();
            if (onHand < medication.getReorderThreshold()) {
                alerts.add(new ReorderAlert("medication", medication.getId(), medication.getName(), onHand, medication.getReorderThreshold()));
            }
        }
        for (InventoryItem item : inventoryItemRepository.findAllByTenantIdAndStatus(tenantId, "active")) {
            if (item.getReorderThreshold() <= 0) {
                continue;
            }
            int onHand = stockBatchRepository.findAllByInventoryItemIdAndTenantId(item.getId(), tenantId).stream()
                    .filter(b -> "active".equals(b.getStatus()))
                    .mapToInt(StockBatch::getQuantityOnHand)
                    .sum();
            if (onHand < item.getReorderThreshold()) {
                alerts.add(new ReorderAlert("inventory_item", item.getId(), item.getName(), onHand, item.getReorderThreshold()));
            }
        }
        return alerts;
    }
}
