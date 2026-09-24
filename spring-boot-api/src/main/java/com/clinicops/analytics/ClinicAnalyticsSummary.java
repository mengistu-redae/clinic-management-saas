package com.clinicops.analytics;

import java.util.List;

/**
 * Bundled response for GET /api/clinic/analytics - the dashboard always
 * needs all four panels at once, so this is one wrapper record rather than
 * four separate endpoints, same "wrapper record" shape as
 * AppointmentSeriesResult/EncounterWithPrescriptions elsewhere in this app.
 */
public record ClinicAnalyticsSummary(
        List<DailyCount> appointmentVolume,
        List<DailyRevenue> revenue,
        List<StatusCount> statusBreakdown,
        List<ProviderAppointmentCount> appointmentsByProvider) {
}
