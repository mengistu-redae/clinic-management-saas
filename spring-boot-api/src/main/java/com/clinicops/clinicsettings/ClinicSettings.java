package com.clinicops.clinicsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One lazy singleton row per tenant - {@code tenantId} is the primary key
 * itself (no separate id), same shape as the reference project's
 * OperatorSettings. Every column is a nullable override; a null value
 * means "use the platform default" (business fields, resolved by
 * ClinicSettingsService.resolve) or "unset" (contact/branding fields, no
 * platform default exists for those). Created lazily on first POST to
 * either /api/clinic/settings or /api/clinic/branding - GET works fine
 * with no row at all.
 *
 * Deliberately split into two disjoint column groups, each written by its
 * own endpoint so one tab's full-replace can never wipe the other's
 * fields (per the kickoff spec):
 * - settings group: taxRatePercent..website, via ClinicSettingsController.
 * - branding group: logoUrl..footerNote, via ClinicBrandingController.
 */
@Entity
@Table(name = "clinic_settings")
@Getter
@Setter
public class ClinicSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    // ---- settings group ----

    @Column(name = "tax_rate_percent")
    private BigDecimal taxRatePercent;

    @Column(name = "reschedule_fee_patient_portal")
    private BigDecimal rescheduleFeePatientPortal;

    @Column(name = "reschedule_fee_front_desk")
    private BigDecimal rescheduleFeeFrontDesk;

    @Column(name = "reschedule_min_notice_hours")
    private Integer rescheduleMinNoticeHours;

    @Column(name = "appointment_reminder_lead_hours")
    private Integer appointmentReminderLeadHours;

    /**
     * A valid IANA zone id (e.g. "Africa/Addis_Ababa"), validated against
     * {@code ZoneId.getAvailableZoneIds()} in ClinicSettingsService - same
     * "backend-defined bounded set validated in code" convention as
     * Allergy.severity/Prescription.route. Null means "use the platform
     * default" (ClinicSettingsService.resolveTimezone), same as every
     * other business field in this group - see phase 18.
     */
    @Column(name = "timezone")
    private String timezone;

    @Column(name = "support_phone")
    private String supportPhone;

    @Column(name = "support_email")
    private String supportEmail;

    private String address;

    private String website;

    // ---- branding group ----

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "brand_color")
    private String brandColor;

    @Column(name = "accent_color")
    private String accentColor;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "footer_note")
    private String footerNote;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
