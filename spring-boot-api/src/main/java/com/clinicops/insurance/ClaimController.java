package com.clinicops.insurance;

import com.clinicops.patient.PatientRepository;
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
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * front_desk + clinic_admin only, matching the role split pinned for this
 * whole module - insurance billing is a billing-team concern, not a
 * clinical one (no provider access, unlike AppointmentPaymentController's
 * own read-only provider carve-out).
 */
@RestController
public class ClaimController {

    private final ClaimRepository claimRepository;
    private final ClaimService claimService;
    private final PatientRepository patientRepository;
    private final CurrentUserService currentUserService;

    public ClaimController(
            ClaimRepository claimRepository,
            ClaimService claimService,
            PatientRepository patientRepository,
            CurrentUserService currentUserService) {
        this.claimRepository = claimRepository;
        this.claimService = claimService;
        this.patientRepository = patientRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/invoices/{invoiceId}/claims")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public List<Claim> claimsForInvoice(@PathVariable UUID invoiceId) {
        UUID tenantId = TenantContext.require();
        return claimRepository.findAllByInvoiceIdAndTenantId(invoiceId, tenantId);
    }

    @PostMapping("/api/invoices/{invoiceId}/claims")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim createClaim(
            @PathVariable UUID invoiceId, @Valid @RequestBody CreateClaimRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID createdBy = currentUserService.resolveInternalUserId(jwt);
        return claimService.createClaim(invoiceId, tenantId, request, createdBy);
    }

    @GetMapping("/api/patients/{patientId}/claims")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public List<Claim> claimsForPatient(@PathVariable UUID patientId) {
        UUID tenantId = TenantContext.require();
        patientRepository.findByIdAndTenantId(patientId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
        return claimRepository.findAllByPatientIdAndTenantId(patientId, tenantId);
    }

    @GetMapping("/api/claims/{id}")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim claim(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        return claimRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Claim not found: " + id));
    }

    @PostMapping("/api/claims/{id}/submit")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim submit(@PathVariable UUID id, @RequestBody SubmitClaimRequest request) {
        UUID tenantId = TenantContext.require();
        return claimService.submit(id, tenantId, request);
    }

    @PostMapping("/api/claims/{id}/record-adjudication")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim recordAdjudication(@PathVariable UUID id, @Valid @RequestBody RecordAdjudicationRequest request) {
        UUID tenantId = TenantContext.require();
        return claimService.recordAdjudication(id, tenantId, request);
    }

    @PostMapping("/api/claims/{id}/appeal")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim appeal(@PathVariable UUID id, @Valid @RequestBody AppealClaimRequest request) {
        UUID tenantId = TenantContext.require();
        return claimService.appeal(id, tenantId, request);
    }

    @PostMapping("/api/claims/{id}/close")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Claim close(@PathVariable UUID id, @RequestBody CloseClaimRequest request) {
        UUID tenantId = TenantContext.require();
        return claimService.close(id, tenantId, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidClaimStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidClaimStatusException e) {
        return e.getMessage();
    }
}
