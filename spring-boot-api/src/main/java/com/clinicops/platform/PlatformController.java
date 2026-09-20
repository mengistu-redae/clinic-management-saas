package com.clinicops.platform;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Cross-tenant by design - platform_admin tokens carry no organization
 * claim (see TenantContext's javadoc), so every method here reads across
 * every clinic, never scoped by TenantContext.
 */
@RestController
@RequestMapping("/api/platform/clinics")
public class PlatformController {

    private final ClinicRepository clinicRepository;
    private final ClinicProvisioningService clinicProvisioningService;

    public PlatformController(ClinicRepository clinicRepository, ClinicProvisioningService clinicProvisioningService) {
        this.clinicRepository = clinicRepository;
        this.clinicProvisioningService = clinicProvisioningService;
    }

    /** Every clinic, any status - an admin needs to see deactivated ones too, in order to reactivate them. */
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Clinic> clinics() {
        return clinicRepository.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic clinic(@PathVariable UUID id) {
        return clinicRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ClinicProvisioningResult createClinic(@Valid @RequestBody CreateClinicRequest request) {
        return clinicProvisioningService.provisionClinic(
                request.name(), request.orgAlias(), request.domain(), request.adminEmail(), request.adminFullName());
    }

    /** Partial update - name only. keycloak_org_id is fixed at creation (see UpdateClinicRequest). */
    @PostMapping("/{id}/update")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic updateClinic(@PathVariable UUID id, @RequestBody UpdateClinicRequest request) {
        Clinic clinic = clinicRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + id));
        if (request.name() != null) {
            clinic.setName(request.name());
        }
        return clinicRepository.save(clinic);
    }

    /**
     * Soft-deactivate via the status column TenantContextFilter/
     * AppointmentService already enforce against (see CLAUDE.md's phase 6
     * write-up) - never a row delete, since a clinic has providers/
     * appointments/invoices underneath it. Idempotent - re-calling on an
     * already-inactive clinic just returns it unchanged, same convention
     * as CheckInService's transitions.
     */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic deactivateClinic(@PathVariable UUID id) {
        return setStatus(id, "inactive");
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic reactivateClinic(@PathVariable UUID id) {
        return setStatus(id, "active");
    }

    private Clinic setStatus(UUID id, String status) {
        Clinic clinic = clinicRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + id));
        if (status.equals(clinic.getStatus())) {
            return clinic; // idempotent re-call
        }
        clinic.setStatus(status);
        return clinicRepository.save(clinic);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(ClinicAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyExists(ClinicAlreadyExistsException e) {
        return e.getMessage();
    }

    @ExceptionHandler(KeycloakAdminException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleKeycloakAdminError(KeycloakAdminException e) {
        return e.getMessage();
    }
}
