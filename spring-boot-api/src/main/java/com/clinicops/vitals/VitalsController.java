package com.clinicops.vitals;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.InvalidAppointmentStatusException;
import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
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

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * provider/clinic_admin/front_desk can all record vitals - the one
 * deliberate exception to this app's "front_desk has zero clinical access"
 * boundary (EncounterController's own convention), made because this app
 * has no "nurse" role and vitals are typically taken by front-desk/nursing
 * staff before a provider ever sees the patient. See Vitals' own javadoc
 * for why this hangs off Appointment, not Encounter.
 */
@RestController
public class VitalsController {

    private final VitalsService vitalsService;
    private final AppointmentRepository appointmentRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public VitalsController(
            VitalsService vitalsService,
            AppointmentRepository appointmentRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.vitalsService = vitalsService;
        this.appointmentRepository = appointmentRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/appointments/{id}/vitals")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'FRONT_DESK')")
    public Vitals upsertVitals(@PathVariable UUID id, @RequestBody UpsertVitalsRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID recordedByUserId = currentUserService.resolveInternalUserId(jwt);
        Vitals vitals = vitalsService.upsert(id, tenantId, recordedByUserId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "vitals", vitals.getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/vitals");
        return vitals;
    }

    @GetMapping("/api/appointments/{id}/vitals")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'FRONT_DESK')")
    public Vitals getVitals(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Vitals vitals = vitalsService.get(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "vitals", vitals.getId(), patientIdForAppointment(id, tenantId), "/api/appointments/{id}/vitals");
        return vitals;
    }

    /** Appointment.patientId is null for a guest-channel booking - vitals still get logged via resourceId, just with no patient to link, same as PhiAccessLog's own nullable patientId. */
    private UUID patientIdForAppointment(UUID appointmentId, UUID tenantId) {
        return appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .map(com.clinicops.appointment.Appointment::getPatientId)
                .orElse(null);
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
