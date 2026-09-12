package com.clinicops.clinic;

import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A minimal endpoint standing in for the phase-1 wiring check: confirms the
 * whole chain (bearer JWT -> Spring Security -> TenantContextFilter ->
 * @PreAuthorize -> a tenant-scoped repository read) works end to end against
 * a real staff login before any domain feature is built on top of it.
 */
@RestController
public class ClinicController {

    private final ClinicRepository clinicRepository;

    public ClinicController(ClinicRepository clinicRepository) {
        this.clinicRepository = clinicRepository;
    }

    @GetMapping("/api/clinic/me")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'PROVIDER', 'FRONT_DESK')")
    public Clinic me() {
        return clinicRepository.findById(TenantContext.require())
                .orElseThrow(() -> new ClinicNotFoundException(
                        "No clinic found for the current tenant - it may have been removed."));
    }

    @ExceptionHandler(ClinicNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleClinicNotFound(ClinicNotFoundException ex) {
        return ex.getMessage();
    }
}
