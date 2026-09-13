package com.clinicops.clinicsettings;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Separate from ClinicSettingsController on purpose - a disjoint column
 * group so one tab's full-replace can never wipe the other's fields (per
 * the kickoff spec), and a different read audience: every staff role needs
 * this to theme their own workspace, not just clinic_admin.
 */
@RestController
public class ClinicBrandingController {

    private final ClinicSettingsService clinicSettingsService;

    public ClinicBrandingController(ClinicSettingsService clinicSettingsService) {
        this.clinicSettingsService = clinicSettingsService;
    }

    @GetMapping("/api/clinic/branding")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public ClinicBrandingView branding() {
        return clinicSettingsService.getBranding(TenantContext.require());
    }

    @PostMapping("/api/clinic/branding")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ClinicBrandingView updateBranding(@Valid @RequestBody UpdateClinicBrandingRequest request) {
        return clinicSettingsService.updateBranding(TenantContext.require(), request);
    }
}
