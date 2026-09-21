package com.clinicops.referral;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Clinical content - provider + clinic_admin only, no front_desk access,
 * same reasoning phase 4 already applied to encounters (routing a patient
 * to a specialist is a clinical judgment call, not front-desk logistics).
 */
@RestController
public class ReferralController {

    private final ReferralService referralService;
    private final PhiAccessAuditService phiAccessAuditService;

    public ReferralController(ReferralService referralService, PhiAccessAuditService phiAccessAuditService) {
        this.referralService = referralService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/referrals")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Referral createReferral(@Valid @RequestBody CreateReferralRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Referral referral = referralService.create(tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "referral", referral.getId(), referral.getPatientId(), "/api/referrals");
        return referral;
    }

    /** One log row per list call, not one per referral - same "a list touches many patients at once" reasoning as PatientController.patients/LabOrderController.labOrders. */
    @GetMapping("/api/referrals")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<Referral> referrals(@AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        phiAccessAuditService.logRead(tenantId, jwt, "referral_list", null, null, "/api/referrals");
        return referralService.listForTenant(tenantId);
    }

    @GetMapping("/api/referrals/{id}")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Referral referral(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Referral referral = referralService.get(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "referral", id, referral.getPatientId(), "/api/referrals/{id}");
        return referral;
    }

    @PostMapping("/api/referrals/{id}/update")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public Referral updateReferral(@PathVariable UUID id, @RequestBody UpdateReferralRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Referral referral = referralService.update(id, tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "referral", id, referral.getPatientId(), "/api/referrals/{id}/update");
        return referral;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
