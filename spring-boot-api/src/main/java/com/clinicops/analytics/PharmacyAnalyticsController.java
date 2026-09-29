package com.clinicops.analytics;

import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Phase 34 - the pharmacist-facing half of the "unified reporting &
 * analytics" module, mirroring ClinicAnalyticsController's own shape (no
 * dedicated service bean, plain read composition over one repository).
 * pharmacist+clinic_admin, matching DispenseController's own gate.
 */
@RestController
public class PharmacyAnalyticsController {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 180;

    private final DispenseRecordRepository dispenseRecordRepository;

    public PharmacyAnalyticsController(DispenseRecordRepository dispenseRecordRepository) {
        this.dispenseRecordRepository = dispenseRecordRepository;
    }

    @GetMapping("/api/pharmacy/analytics")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PharmacyAnalyticsSummary analytics(@RequestParam(name = "days", required = false) Integer days) {
        UUID tenantId = TenantContext.require();
        int windowDays = days == null ? DEFAULT_WINDOW_DAYS : Math.max(1, Math.min(days, MAX_WINDOW_DAYS));
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        return new PharmacyAnalyticsSummary(
                dispenseRecordRepository.findDailyDispenseVolume(tenantId, since),
                dispenseRecordRepository.countByMedicationSince(tenantId, since));
    }
}
