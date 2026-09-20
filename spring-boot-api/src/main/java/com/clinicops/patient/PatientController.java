package com.clinicops.patient;

import com.clinicops.phiaudit.PhiAccessAuditService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
public class PatientController {

    private final PatientRepository patientRepository;
    private final PatientWriter patientWriter;
    private final PhiAccessAuditService phiAccessAuditService;

    public PatientController(PatientRepository patientRepository, PatientWriter patientWriter, PhiAccessAuditService phiAccessAuditService) {
        this.patientRepository = patientRepository;
        this.patientWriter = patientWriter;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    /** Registering a walk-in - front-desk's entry point before booking them an appointment. */
    @PostMapping("/api/patients")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Patient createPatient(@Valid @RequestBody CreatePatientRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Patient patient = patientWriter.register(tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "patient", patient.getId(), patient.getId(), "/api/patients");
        return patient;
    }

    /** One log row per search/list call, not one per result - a query touches many patients at once, not one specific chart. */
    @GetMapping("/api/patients")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<Patient> patients(@RequestParam(required = false) String query, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        phiAccessAuditService.logRead(tenantId, jwt, "patient_search", null, null, "/api/patients");
        return (query == null || query.isBlank())
                ? patientRepository.findAllByTenantId(tenantId)
                : patientRepository.search(tenantId, query);
    }

    @GetMapping("/api/patients/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public Patient patient(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Patient patient = patientRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + id));
        phiAccessAuditService.logRead(tenantId, jwt, "patient", id, id, "/api/patients/{id}");
        return patient;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
