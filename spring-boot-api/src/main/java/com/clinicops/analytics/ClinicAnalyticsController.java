package com.clinicops.analytics;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.payment.PaymentRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Read-only aggregation for the clinic-admin dashboard's analytics panels
 * (frontend phase O) - a genuinely new backend slice, not derived from any
 * existing list endpoint (unlike phase A's original dashboards, which
 * deliberately composed summaries from endpoints that already existed).
 * clinic_admin-only - front_desk/provider keep their own narrower
 * dashboards unchanged. One bundled response (ClinicAnalyticsSummary)
 * rather than four separate endpoints, since the dashboard always needs
 * every panel at once. No dedicated service bean - this is plain read
 * composition across two repositories, the same "controller calls
 * repositories directly" precedent phase 5's CRUD controllers already set,
 * not the kind of cross-cutting/transactional logic that earns a service.
 */
@RestController
public class ClinicAnalyticsController {

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int MAX_WINDOW_DAYS = 180;

    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;

    public ClinicAnalyticsController(AppointmentRepository appointmentRepository, PaymentRepository paymentRepository) {
        this.appointmentRepository = appointmentRepository;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/api/clinic/analytics")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ClinicAnalyticsSummary analytics(@RequestParam(name = "days", required = false) Integer days) {
        UUID tenantId = TenantContext.require();
        int windowDays = days == null ? DEFAULT_WINDOW_DAYS : Math.max(1, Math.min(days, MAX_WINDOW_DAYS));
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        return new ClinicAnalyticsSummary(
                appointmentRepository.findDailyAppointmentVolume(tenantId, since),
                paymentRepository.findDailyRevenue(tenantId, since),
                appointmentRepository.countByStatus(tenantId),
                appointmentRepository.countByProviderSince(tenantId, since));
    }
}
