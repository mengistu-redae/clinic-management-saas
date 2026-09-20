package com.clinicops.allergy;

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
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Same access gate as PatientController exactly - allergies extend the
 * patient record itself, not a specific encounter, so front_desk (which
 * already writes patient demographics) writes these too, not just clinical
 * staff. Revisit if that turns out too permissive in practice - there's no
 * strong existing-codebase precedent forcing this either way, this is a
 * judgment call, not a mirrored decision.
 */
@RestController
public class AllergyController {

    private static final Set<String> VALID_SEVERITIES = Set.of("mild", "moderate", "severe");
    private static final Set<String> VALID_STATUSES = Set.of("active", "resolved", "unconfirmed");

    private final AllergyRepository allergyRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public AllergyController(
            AllergyRepository allergyRepository,
            PatientRepository patientRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.allergyRepository = allergyRepository;
        this.patientRepository = patientRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/patients/{patientId}/allergies")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<Allergy> allergies(@PathVariable UUID patientId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "allergy_list", null, patientId, "/api/patients/{patientId}/allergies");
        return allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId);
    }

    @PostMapping("/api/patients/{patientId}/allergies")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Allergy createAllergy(
            @PathVariable UUID patientId, @Valid @RequestBody CreateAllergyRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);

        String severity = request.severity() != null ? request.severity() : "moderate";
        if (!VALID_SEVERITIES.contains(severity)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "severity must be one of " + VALID_SEVERITIES);
        }

        Allergy allergy = new Allergy();
        allergy.setTenantId(tenantId);
        allergy.setPatientId(patientId);
        allergy.setAllergen(request.allergen());
        allergy.setReactionType(request.reactionType());
        allergy.setSeverity(severity);
        allergy.setIdentifiedAt(request.identifiedAt());
        allergy.setRecordedBy(currentUserService.resolveInternalUserId(jwt));
        allergy = allergyRepository.save(allergy);

        phiAccessAuditService.logWrite(tenantId, jwt, "allergy", allergy.getId(), patientId, "/api/patients/{patientId}/allergies");
        return allergy;
    }

    /** Partial update - reactionType/severity/status only. allergen/patientId are fixed at creation; correct a mistaken entry with a new row instead. */
    @PostMapping("/api/patients/{patientId}/allergies/{id}/update")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Allergy updateAllergy(
            @PathVariable UUID patientId, @PathVariable UUID id, @RequestBody UpdateAllergyRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Allergy allergy = allergyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Allergy not found: " + id));
        if (!allergy.getPatientId().equals(patientId)) {
            throw new NoSuchElementException("Allergy not found: " + id);
        }

        if (request.reactionType() != null) {
            allergy.setReactionType(request.reactionType());
        }
        if (request.severity() != null) {
            if (!VALID_SEVERITIES.contains(request.severity())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "severity must be one of " + VALID_SEVERITIES);
            }
            allergy.setSeverity(request.severity());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            allergy.setStatus(request.status());
        }
        allergy.setUpdatedAt(Instant.now());
        allergy = allergyRepository.save(allergy);

        phiAccessAuditService.logWrite(tenantId, jwt, "allergy", allergy.getId(), patientId, "/api/patients/{patientId}/allergies/{id}/update");
        return allergy;
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
