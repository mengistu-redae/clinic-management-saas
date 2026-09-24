package com.clinicops.clinicsettings;

import java.math.BigDecimal;

/**
 * Full-replace: every field is applied unconditionally (a null field
 * reverts that column to null, i.e. back to the platform default for the
 * business fields) - unlike the plain-CRUD resources elsewhere in phase 5,
 * which use partial-update semantics instead. See ClinicSettingsService.
 */
public record UpdateClinicSettingsRequest(
        BigDecimal taxRatePercent,
        BigDecimal rescheduleFeePatientPortal,
        BigDecimal rescheduleFeeFrontDesk,
        Integer rescheduleMinNoticeHours,
        Integer appointmentReminderLeadHours,
        /** A valid IANA zone id, or null to revert to the platform default - validated in ClinicSettingsService.updateSettings. */
        String timezone,
        String supportPhone,
        String supportEmail,
        String address,
        String website
) {
}
