package com.clinicops.clinicsettings;

/**
 * GET /api/clinic/branding's shape - {@code displayName} falls back to the
 * clinic's own legal name when unset (mirrors the reference project's
 * OperatorBrandingView); every other field stays null if unset - the
 * frontend applies its own default fallback, the platform stores none.
 *
 * {@code timezone} is the one field here that ISN'T unset-if-absent - it's
 * always the fully-resolved value (override or platform default), same as
 * {@link EffectiveClinicSettings#timezone()} - added for phase 18's
 * frontend timezone-display picker. It lives on this endpoint rather than
 * only the clinic_admin-only settings endpoint deliberately: front_desk/
 * provider staff and a logged-out patient/guest all need to read a
 * clinic's own timezone to offer "show times in the clinic's timezone" as
 * an option, and this is the one clinic-config endpoint already readable
 * by all three staff roles (a further public read, for guest/patient, is
 * exposed on the clinic directory - see ClinicDirectoryView). Non
 * -sensitive operational data, not a privacy concern.
 */
public record ClinicBrandingView(
        String logoUrl,
        String brandColor,
        String accentColor,
        String displayName,
        String footerNote,
        String timezone
) {
}
