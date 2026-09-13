package com.clinicops.clinicsettings;

/**
 * GET /api/clinic/branding's shape - {@code displayName} falls back to the
 * clinic's own legal name when unset (mirrors the reference project's
 * OperatorBrandingView); every other field stays null if unset - the
 * frontend applies its own default fallback, the platform stores none.
 */
public record ClinicBrandingView(
        String logoUrl,
        String brandColor,
        String accentColor,
        String displayName,
        String footerNote
) {
}
