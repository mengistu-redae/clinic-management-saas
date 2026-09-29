package com.clinicops.analytics;

import java.util.List;

/**
 * Bundled response for GET /api/pharmacy/analytics - same "one wrapper
 * record rather than several endpoints" shape as ClinicAnalyticsSummary.
 */
public record PharmacyAnalyticsSummary(
        List<DailyCount> dispensingVolume,
        List<MedicationDispenseCount> medicationDispenseCounts) {
}
