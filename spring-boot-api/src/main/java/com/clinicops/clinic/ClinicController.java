package com.clinicops.clinic;

import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A minimal endpoint standing in for the phase-1 wiring check: confirms the
 * whole chain (bearer JWT -> Spring Security -> TenantContextFilter ->
 * @PreAuthorize -> a tenant-scoped repository read) works end to end against
 * a real staff login before any domain feature is built on top of it.
 */
@RestController
public class ClinicController {

    private final ClinicRepository clinicRepository;
    private final ClinicSettingsService clinicSettingsService;

    public ClinicController(ClinicRepository clinicRepository, ClinicSettingsService clinicSettingsService) {
        this.clinicRepository = clinicRepository;
        this.clinicSettingsService = clinicSettingsService;
    }

    @GetMapping("/api/clinic/me")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'PROVIDER', 'FRONT_DESK')")
    public Clinic me() {
        return clinicRepository.findById(TenantContext.require())
                .orElseThrow(() -> new ClinicNotFoundException(
                        "No clinic found for the current tenant - it may have been removed."));
    }

    /**
     * The patient-portal/guest entry point into booking: pick a clinic
     * first, then browse that one clinic's availability (see
     * AvailabilityController) - no cross-tenant "marketplace" search, since
     * clinics define their own appointment_types independently with no
     * shared cross-clinic identity to search by.
     */
    @GetMapping("/api/clinics")
    public List<ClinicDirectoryView> clinics() {
        return clinicRepository.findAllByStatusOrderByName("active").stream()
                .map(clinic -> ClinicDirectoryView.from(clinic, clinicSettingsService.resolve(clinic.getId()).timezone()))
                .toList();
    }

    @ExceptionHandler(ClinicNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleClinicNotFound(ClinicNotFoundException ex) {
        return ex.getMessage();
    }
}
