package com.clinicops.consent;

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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Same access gate as AllergyController/PatientController exactly -
 * consent extends the patient record itself, collected at intake, not a
 * clinical judgment call (reinforced by the decision not to introduce a
 * "nurse" role for this phase). No update/delete endpoint at all - see
 * ConsentRecord's own javadoc, genuinely immutable once created.
 */
@RestController
public class ConsentController {

    private static final Set<String> VALID_TYPES = Set.of("general_treatment", "privacy_data");

    private final ConsentRecordRepository consentRecordRepository;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public ConsentController(
            ConsentRecordRepository consentRecordRepository,
            PatientRepository patientRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.consentRecordRepository = consentRecordRepository;
        this.patientRepository = patientRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/patients/{patientId}/consent-records")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<ConsentRecord> consentRecords(@PathVariable UUID patientId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "consent_record_list", null, patientId, "/api/patients/{patientId}/consent-records");
        return consentRecordRepository.findAllByPatientId(patientId);
    }

    @PostMapping("/api/patients/{patientId}/consent-records")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public ConsentRecord createConsentRecord(
            @PathVariable UUID patientId, @Valid @RequestBody CreateConsentRecordRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        if (!VALID_TYPES.contains(request.consentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "consentType must be one of " + VALID_TYPES);
        }

        ConsentRecord record = new ConsentRecord();
        record.setTenantId(tenantId);
        record.setPatientId(patientId);
        record.setConsentType(request.consentType());
        record.setPolicyVersion(request.policyVersion());
        record.setConsentGiven(request.consentGiven() == null || request.consentGiven());
        record.setWitnessName(request.witnessName());
        record.setLanguagePresented(request.languagePresented());
        record.setDataSharingPreferences(request.dataSharingPreferences());
        record.setRecordedBy(currentUserService.resolveInternalUserId(jwt));
        record = consentRecordRepository.save(record);

        phiAccessAuditService.logWrite(tenantId, jwt, "consent_record", record.getId(), patientId, "/api/patients/{patientId}/consent-records");
        return record;
    }

    private void requireOwnedPatient(UUID patientId, UUID tenantId) {
        patientRepository.findAccessible(patientId, tenantId, TenantContext.clinicGroupId())
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
