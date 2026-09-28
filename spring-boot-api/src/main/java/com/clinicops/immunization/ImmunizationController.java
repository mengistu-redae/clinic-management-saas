package com.clinicops.immunization;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.patient.PatientRepository;
import com.clinicops.phiaudit.PhiAccessAuditService;
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

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Unlike AllergyController's front_desk+clinic_admin-only write gate,
 * immunizations get the same 3-role gate as VitalsController
 * (provider+clinic_admin+front_desk) - an immunization is physically
 * administered, the same real-world shape as taking vitals, and this app
 * has no "nurse" role to route that through instead. A judgment call, not
 * mirrored from an explicit user answer - flagged as revisitable the same
 * way Allergy's own gate was.
 */
@RestController
public class ImmunizationController {

    private final ImmunizationRepository immunizationRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public ImmunizationController(
            ImmunizationRepository immunizationRepository,
            PatientRepository patientRepository,
            AppointmentRepository appointmentRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.immunizationRepository = immunizationRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/patients/{patientId}/immunizations")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'FRONT_DESK')")
    public List<Immunization> immunizations(@PathVariable UUID patientId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "immunization_list", null, patientId, "/api/patients/{patientId}/immunizations");
        return immunizationRepository.findAllByPatientIdAndTenantId(patientId, tenantId);
    }

    @PostMapping("/api/patients/{patientId}/immunizations")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'FRONT_DESK')")
    public Immunization createImmunization(
            @PathVariable UUID patientId, @Valid @RequestBody CreateImmunizationRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        if (request.appointmentId() != null) {
            appointmentRepository.findByIdAndTenantId(request.appointmentId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + request.appointmentId()));
        }

        Immunization immunization = new Immunization();
        immunization.setTenantId(tenantId);
        immunization.setPatientId(patientId);
        immunization.setVaccineName(request.vaccineName());
        immunization.setAdministeredAt(request.administeredAt());
        immunization.setDoseNumber(request.doseNumber());
        immunization.setLotNumber(request.lotNumber());
        immunization.setSite(request.site());
        immunization.setAppointmentId(request.appointmentId());
        immunization.setRecordedBy(currentUserService.resolveInternalUserId(jwt));
        immunization = immunizationRepository.save(immunization);

        phiAccessAuditService.logWrite(tenantId, jwt, "immunization", immunization.getId(), patientId, "/api/patients/{patientId}/immunizations");
        return immunization;
    }

    /** Partial update - doseNumber/lotNumber/site only. vaccineName/administeredAt/patientId are fixed at creation; correct a mistaken entry with a new row instead. */
    @PostMapping("/api/patients/{patientId}/immunizations/{id}/update")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'FRONT_DESK')")
    public Immunization updateImmunization(
            @PathVariable UUID patientId, @PathVariable UUID id, @RequestBody UpdateImmunizationRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Immunization immunization = immunizationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Immunization not found: " + id));
        if (!immunization.getPatientId().equals(patientId)) {
            throw new NoSuchElementException("Immunization not found: " + id);
        }

        if (request.doseNumber() != null) {
            immunization.setDoseNumber(request.doseNumber());
        }
        if (request.lotNumber() != null) {
            immunization.setLotNumber(request.lotNumber());
        }
        if (request.site() != null) {
            immunization.setSite(request.site());
        }
        immunization.setUpdatedAt(Instant.now());
        immunization = immunizationRepository.save(immunization);

        phiAccessAuditService.logWrite(tenantId, jwt, "immunization", immunization.getId(), patientId, "/api/patients/{patientId}/immunizations/{id}/update");
        return immunization;
    }

    private void requireOwnedPatient(UUID patientId, UUID tenantId) {
        patientRepository.findByIdAndTenantId(patientId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
