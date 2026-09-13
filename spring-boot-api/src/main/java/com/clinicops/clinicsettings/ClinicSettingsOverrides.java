package com.clinicops.clinicsettings;

import java.math.BigDecimal;

/** The settings-group columns exactly as stored - null means "not overridden," unlike {@link EffectiveClinicSettings}. */
public record ClinicSettingsOverrides(
        BigDecimal taxRatePercent,
        BigDecimal rescheduleFeePatientPortal,
        BigDecimal rescheduleFeeFrontDesk,
        Integer rescheduleMinNoticeHours,
        Integer appointmentReminderLeadHours,
        String supportPhone,
        String supportEmail,
        String address,
        String website
) {
    static ClinicSettingsOverrides from(ClinicSettings s) {
        if (s == null) {
            return new ClinicSettingsOverrides(null, null, null, null, null, null, null, null, null);
        }
        return new ClinicSettingsOverrides(
                s.getTaxRatePercent(), s.getRescheduleFeePatientPortal(), s.getRescheduleFeeFrontDesk(),
                s.getRescheduleMinNoticeHours(), s.getAppointmentReminderLeadHours(),
                s.getSupportPhone(), s.getSupportEmail(), s.getAddress(), s.getWebsite());
    }
}
