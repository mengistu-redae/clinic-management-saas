package com.clinicops.medicalhistory;

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
 * Same three-role gate as Vitals (phase 8), for the same reason - this is
 * typically collected as part of intake/registration paperwork (patient
 * self-report transcribed by whoever hands them the form), not a clinical
 * judgment call the way an Encounter's own assessment is, so front_desk
 * isn't excluded the way it is from EncounterController.
 */
@RestController
public class MedicalHistoryController {

    private final MedicalHistoryService medicalHistoryService;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public MedicalHistoryController(
            MedicalHistoryService medicalHistoryService,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.medicalHistoryService = medicalHistoryService;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/patients/{patientId}/medical-history")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public MedicalHistory upsertMedicalHistory(
            @PathVariable UUID patientId, @RequestBody UpsertMedicalHistoryRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID recordedByUserId = currentUserService.resolveInternalUserId(jwt);
        MedicalHistory history = medicalHistoryService.upsert(patientId, tenantId, recordedByUserId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "medical_history", patientId, patientId, "/api/patients/{patientId}/medical-history");
        return history;
    }

    @GetMapping("/api/patients/{patientId}/medical-history")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public MedicalHistory getMedicalHistory(@PathVariable UUID patientId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        MedicalHistory history = medicalHistoryService.get(patientId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "medical_history", patientId, patientId, "/api/patients/{patientId}/medical-history");
        return history;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
