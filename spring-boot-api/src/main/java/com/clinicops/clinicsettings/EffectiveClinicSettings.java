package com.clinicops.clinicsettings;

import java.math.BigDecimal;

/**
 * The fully-resolved settings-group values - business fields (tax rate,
 * fees, notice hours, reminder lead) are always non-null (an override
 * coalesced with the platform default); contact fields pass through as-is
 * and can be null (no platform default exists for those). Returned by
 * ClinicSettingsService.resolve(tenantId) - the one merge point every
 * consumer (RescheduleService, this settings API) reads, never the
 * platform @Value defaults directly.
 */
public record EffectiveClinicSettings(
        BigDecimal taxRatePercent,
        BigDecimal rescheduleFeePatientPortal,
        BigDecimal rescheduleFeeFrontDesk,
        long rescheduleMinNoticeHours,
        int appointmentReminderLeadHours,
        /** Always non-null (an override coalesced with the platform default) - see phase 18. */
        String timezone,
        String supportPhone,
        String supportEmail,
        String address,
        String website
) {
}
