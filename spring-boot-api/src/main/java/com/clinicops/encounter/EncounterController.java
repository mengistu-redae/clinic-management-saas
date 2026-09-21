package com.clinicops.encounter;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.InvalidAppointmentStatusException;
import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.provider.CurrentProviderService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
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
    private final CurrentUserService currentUserService;
    private final AppointmentRepository appointmentRepository;
    private final PhiAccessAuditService phiAccessAuditService;

    public EncounterController(
            EncounterService encounterService,
            CurrentProviderService currentProviderService,
            CurrentUserService currentUserService,
            AppointmentRepository appointmentRepository,
            PhiAccessAuditService phiAccessAuditService) {
        this.encounterService = encounterService;
        this.currentProviderService = currentProviderService;
        this.currentUserService = currentUserService;
        this.appointmentRepository = appointmentRepository;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/appointments/{id}/encounter")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Encounter upsertEncounter(
            @PathVariable UUID id, @RequestBody UpsertEncounterRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        Encounter encounter = encounterService.upsert(id, tenantId, actingProviderId,
                request.chiefComplaint(), request.assessment(), request.plan(), request.icd10Codes());
        phiAccessAuditService.logWrite(tenantId, jwt, "encounter", encounter.getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/encounter");
        return encounter;
    }

    @GetMapping("/api/appointments/{id}/encounter")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public EncounterWithPrescriptions getEncounter(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        EncounterWithPrescriptions result = encounterService.get(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "encounter", result.encounter().getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/encounter");
        return result;
    }

    @PostMapping("/api/appointments/{id}/encounter/prescriptions")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<Prescription> replacePrescriptions(
            @PathVariable UUID id, @Valid @RequestBody List<PrescriptionInput> items, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        List<Prescription> prescriptions = encounterService.replacePrescriptions(id, tenantId, actingProviderId, items);
        phiAccessAuditService.logWrite(tenantId, jwt, "prescription", id, patientIdForAppointment(id, tenantId), "/api/appointments/{id}/encounter/prescriptions");
        return prescriptions;
    }

    /** No request body - a pure state transition, same shape as CheckInController's own no-body transitions. Idempotent re-call - see EncounterService.sign. */
    @PostMapping("/api/appointments/{id}/encounter/sign")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Encounter signEncounter(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        UUID signedByUserId = currentUserService.resolveInternalUserId(jwt);
        Encounter encounter = encounterService.sign(id, tenantId, actingProviderId, signedByUserId);
        phiAccessAuditService.logWrite(tenantId, jwt, "encounter", encounter.getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/encounter/sign");
        return encounter;
    }

    /** Only reachable once the encounter is signed - see EncounterService.addAddendum. */
    @PostMapping("/api/appointments/{id}/encounter/addenda")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public EncounterAddendum addAddendum(
            @PathVariable UUID id, @Valid @RequestBody AddendumInput request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID actingProviderId = actingProviderIdOrNull(jwt, tenantId);
        UUID authorUserId = currentUserService.resolveInternalUserId(jwt);
        EncounterAddendum addendum = encounterService.addAddendum(id, tenantId, actingProviderId, authorUserId, request.text());
        phiAccessAuditService.logWrite(tenantId, jwt, "encounter_addendum", addendum.getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/encounter/addenda");
        return addendum;
    }

    /** Encounter has no patientId of its own (only appointmentId) - resolved via the appointment it belongs to. Null for a guest-channel appointment (no Patient row at all). */
    private UUID patientIdForAppointment(UUID appointmentId, UUID tenantId) {
        return appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .map(com.clinicops.appointment.Appointment::getPatientId)
                .orElse(null);
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

    @ExceptionHandler(EncounterLockedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleLocked(EncounterLockedException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
