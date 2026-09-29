package com.clinicops.analytics;

import com.clinicops.inventory.ReorderAlert;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bundled response for GET /api/inventory/analytics. Every field here is a
 * current-state snapshot, not a historical series - unlike
 * PharmacyAnalyticsSummary's dispensingVolume/medicationDispenseCounts,
 * none of these are windowed by the endpoint's own `days` parameter (see
 * InventoryAnalyticsController's own javadoc for why).
 */
public record InventoryAnalyticsSummary(
        BigDecimal totalValuation,
        List<ReorderAlert> lowStock,
        List<ExpiringBatch> expiringSoon,
        List<StatusCount> assetStatusCounts) {
}
