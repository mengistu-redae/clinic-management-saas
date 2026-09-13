package com.clinicops.clinicsettings;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The one merge point the kickoff spec asks for: holds the platform
 * @Value defaults (moved here from RescheduleService, which now calls
 * resolve(...) instead of injecting them itself) and coalesces each
 * nullable per-tenant override over them. Every consumer - this service's
 * own getSettings/getBranding, and RescheduleService - reads resolve(...),
 * never the @Value fields directly.
 */
@Service
public class ClinicSettingsService {

    private final ClinicSettingsRepository clinicSettingsRepository;
    private final ClinicRepository clinicRepository;
    private final BigDecimal defaultTaxRatePercent;
    private final BigDecimal defaultFeePatientPortal;
    private final BigDecimal defaultFeeFrontDesk;
    private final long defaultMinNoticeHours;
    private final int defaultReminderLeadHours;

    public ClinicSettingsService(
            ClinicSettingsRepository clinicSettingsRepository,
            ClinicRepository clinicRepository,
            @Value("${clinicops.appointment.tax-rate}") BigDecimal defaultTaxRatePercent,
            @Value("${clinicops.appointment.reschedule.fee-patient-portal}") BigDecimal defaultFeePatientPortal,
            @Value("${clinicops.appointment.reschedule.fee-front-desk}") BigDecimal defaultFeeFrontDesk,
            @Value("${clinicops.appointment.reschedule.min-notice-hours}") long defaultMinNoticeHours,
            @Value("${clinicops.appointment.reminder-lead-hours}") int defaultReminderLeadHours) {
        this.clinicSettingsRepository = clinicSettingsRepository;
        this.clinicRepository = clinicRepository;
        this.defaultTaxRatePercent = defaultTaxRatePercent;
        this.defaultFeePatientPortal = defaultFeePatientPortal;
        this.defaultFeeFrontDesk = defaultFeeFrontDesk;
        this.defaultMinNoticeHours = defaultMinNoticeHours;
        this.defaultReminderLeadHours = defaultReminderLeadHours;
    }

    /** Safe for a null tenantId (returns pure defaults) - never throws. */
    public EffectiveClinicSettings resolve(UUID tenantId) {
        ClinicSettings s = tenantId == null ? null : clinicSettingsRepository.findById(tenantId).orElse(null);
        return new EffectiveClinicSettings(
                s != null && s.getTaxRatePercent() != null ? s.getTaxRatePercent() : defaultTaxRatePercent,
                s != null && s.getRescheduleFeePatientPortal() != null ? s.getRescheduleFeePatientPortal() : defaultFeePatientPortal,
                s != null && s.getRescheduleFeeFrontDesk() != null ? s.getRescheduleFeeFrontDesk() : defaultFeeFrontDesk,
                s != null && s.getRescheduleMinNoticeHours() != null ? s.getRescheduleMinNoticeHours() : defaultMinNoticeHours,
                s != null && s.getAppointmentReminderLeadHours() != null ? s.getAppointmentReminderLeadHours() : defaultReminderLeadHours,
                s != null ? s.getSupportPhone() : null,
                s != null ? s.getSupportEmail() : null,
                s != null ? s.getAddress() : null,
                s != null ? s.getWebsite() : null);
    }

    public ClinicSettingsResponse getSettings(UUID tenantId) {
        ClinicSettings s = clinicSettingsRepository.findById(tenantId).orElse(null);
        ClinicSettingsDefaults defaults = new ClinicSettingsDefaults(
                defaultTaxRatePercent, defaultFeePatientPortal, defaultFeeFrontDesk,
                defaultMinNoticeHours, defaultReminderLeadHours);
        return new ClinicSettingsResponse(ClinicSettingsOverrides.from(s), resolve(tenantId), defaults);
    }

    @Transactional
    public ClinicSettingsResponse updateSettings(UUID tenantId, UpdateClinicSettingsRequest request) {
        ClinicSettings s = clinicSettingsRepository.findById(tenantId).orElseGet(() -> {
            ClinicSettings fresh = new ClinicSettings();
            fresh.setTenantId(tenantId);
            return fresh;
        });
        s.setTaxRatePercent(request.taxRatePercent());
        s.setRescheduleFeePatientPortal(request.rescheduleFeePatientPortal());
        s.setRescheduleFeeFrontDesk(request.rescheduleFeeFrontDesk());
        s.setRescheduleMinNoticeHours(request.rescheduleMinNoticeHours());
        s.setAppointmentReminderLeadHours(request.appointmentReminderLeadHours());
        s.setSupportPhone(request.supportPhone());
        s.setSupportEmail(request.supportEmail());
        s.setAddress(request.address());
        s.setWebsite(request.website());
        s.setUpdatedAt(Instant.now());
        clinicSettingsRepository.save(s);
        return getSettings(tenantId);
    }

    public ClinicBrandingView getBranding(UUID tenantId) {
        ClinicSettings s = clinicSettingsRepository.findById(tenantId).orElse(null);
        String displayName = s != null && s.getDisplayName() != null
                ? s.getDisplayName()
                : clinicRepository.findById(tenantId).map(Clinic::getName).orElse(null);
        return new ClinicBrandingView(
                s != null ? s.getLogoUrl() : null,
                s != null ? s.getBrandColor() : null,
                s != null ? s.getAccentColor() : null,
                displayName,
                s != null ? s.getFooterNote() : null);
    }

    @Transactional
    public ClinicBrandingView updateBranding(UUID tenantId, UpdateClinicBrandingRequest request) {
        ClinicSettings s = clinicSettingsRepository.findById(tenantId).orElseGet(() -> {
            ClinicSettings fresh = new ClinicSettings();
            fresh.setTenantId(tenantId);
            return fresh;
        });
        s.setLogoUrl(request.logoUrl());
        s.setBrandColor(request.brandColor());
        s.setAccentColor(request.accentColor());
        s.setDisplayName(request.displayName());
        s.setFooterNote(request.footerNote());
        s.setUpdatedAt(Instant.now());
        clinicSettingsRepository.save(s);
        return getBranding(tenantId);
    }
}
