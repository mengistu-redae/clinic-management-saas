package com.clinicops.encounter;

import com.clinicops.appointment.InvalidAppointmentStatusException;
import com.clinicops.provider.CurrentProviderService;
import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Clinical content - provider + clinic_admin only, front_desk gets no
 * access here at all (matches the "provider" realm role's own description,
 * "Clinician - documents visits"; front_desk's role is booking/check-in,
 * not clinical). Mutations are POST everywhere in this codebase (confirmed
 * zero @PutMapping/@PatchMapping usage) - kept consistent here too, even
 * though the encounter upsert is really a REST "PUT."
 */
@RestController
public class EncounterController {

    private final EncounterService encounterService;
    private final CurrentProviderService currentProviderService;

    public EncounterController(EncounterService encounterService, CurrentProviderService currentProviderService) {
        this.encounterService = encounterService;
        this.currentProviderService = currentProviderService;
    }

    @PostMapping("/api/appointments/{id}/encounter")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Encounter upsertEncounter(
            @PathVariable UUID id, @RequestBody UpsertEncounterRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        return encounterService.upsert(id, tenantId, actingProviderId,
                request.chiefComplaint(), request.assessment(), request.plan());
    }

    @GetMapping("/api/appointments/{id}/encounter")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public EncounterWithPrescriptions getEncounter(@PathVariable UUID id) {
        return encounterService.get(id, TenantContext.require());
    }

    @PostMapping("/api/appointments/{id}/encounter/prescriptions")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<Prescription> replacePrescriptions(
            @PathVariable UUID id, @Valid @RequestBody List<PrescriptionInput> items, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        return encounterService.replacePrescriptions(id, tenantId, actingProviderId, items);
    }

    /** null = caller is clinic_admin, skip the ownership check; otherwise the caller's own Provider.id. */
    private UUID actingProviderIdOrNull(Jwt jwt, UUID tenantId) {
        if (!hasRole(jwt, "PROVIDER")) {
            return null;
        }
        return currentProviderService.resolveProviderId(jwt, tenantId);
    }

    private boolean hasRole(Jwt jwt, String role) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        var roles = (List<String>) realmAccess.get("roles");
        return roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    @ExceptionHandler(InvalidAppointmentStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidAppointmentStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
