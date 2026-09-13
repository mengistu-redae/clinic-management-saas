package com.clinicops.clinicsettings;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClinicSettingsController {

    private final ClinicSettingsService clinicSettingsService;

    public ClinicSettingsController(ClinicSettingsService clinicSettingsService) {
        this.clinicSettingsService = clinicSettingsService;
    }

    @GetMapping("/api/clinic/settings")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ClinicSettingsResponse settings() {
        return clinicSettingsService.getSettings(TenantContext.require());
    }

    /** Full replace of the settings-group override set - a null field reverts that column to the platform default. */
    @PostMapping("/api/clinic/settings")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ClinicSettingsResponse updateSettings(@Valid @RequestBody UpdateClinicSettingsRequest request) {
        return clinicSettingsService.updateSettings(TenantContext.require(), request);
    }
}
