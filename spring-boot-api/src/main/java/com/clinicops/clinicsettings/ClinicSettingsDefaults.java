package com.clinicops.clinicsettings;

import java.math.BigDecimal;

/** The platform-wide fallback values (application.yml's clinicops.appointment.* block) - only the business fields have a default; contact fields don't. */
public record ClinicSettingsDefaults(
        BigDecimal taxRatePercent,
        BigDecimal rescheduleFeePatientPortal,
        BigDecimal rescheduleFeeFrontDesk,
        long rescheduleMinNoticeHours,
        int appointmentReminderLeadHours,
        String timezone
) {
}
