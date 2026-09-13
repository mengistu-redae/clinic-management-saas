package com.clinicops.clinicsettings;

/** GET /api/clinic/settings's exact shape per the kickoff spec: {overrides, effective, defaults}. */
public record ClinicSettingsResponse(
        ClinicSettingsOverrides overrides,
        EffectiveClinicSettings effective,
        ClinicSettingsDefaults defaults
) {
}
