package com.clinicops.clinicsettings;

import jakarta.validation.constraints.Pattern;

/** Full-replace of the branding-only column group. Every field nullable - null clears that column, no platform fallback is stored server-side. */
public record UpdateClinicBrandingRequest(
        @Pattern(regexp = "^https?://.+$", message = "logoUrl must be an http(s) URL") String logoUrl,
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "brandColor must be a #rrggbb hex color") String brandColor,
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "accentColor must be a #rrggbb hex color") String accentColor,
        String displayName,
        String footerNote
) {
}
