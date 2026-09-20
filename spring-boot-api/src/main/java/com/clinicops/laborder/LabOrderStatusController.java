package com.clinicops.laborder;

import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
public class LabOrderStatusController {

    private final LabOrderStatusService labOrderStatusService;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public LabOrderStatusController(
            LabOrderStatusService labOrderStatusService, CurrentUserService currentUserService, PhiAccessAuditService phiAccessAuditService) {
        this.labOrderStatusService = labOrderStatusService;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/lab-orders/{id}/collect-specimen")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests collectSpecimen(@PathVariable UUID id, @RequestBody(required = false) CollectSpecimenRequest request, @AuthenticationPrincipal Jwt jwt) {
        String presentedIdNumber = request != null ? request.presentedIdNumber() : null;
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderStatusService.collectSpecimen(id, tenantId, presentedIdNumber);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/collect-specimen");
        return result;
    }

    @PostMapping("/api/lab-orders/{id}/send")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests send(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderStatusService.send(id, tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/send");
        return result;
    }

    /** Entering results is the single most sensitive write this app has - the actual clinical values. */
    @PostMapping("/api/lab-orders/{id}/result")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests result(@PathVariable UUID id, @RequestBody ResultLabOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID resultedByUserId = currentUserService.resolveInternalUserId(jwt);
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderStatusService.result(id, tenantId, resultedByUserId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/result");
        return result;
    }

    @PostMapping("/api/lab-orders/{id}/review")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests review(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID reviewedByUserId = currentUserService.resolveInternalUserId(jwt);
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderStatusService.review(id, tenantId, reviewedByUserId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/review");
        return result;
    }

    @ExceptionHandler(InvalidLabOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidLabOrderStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(IdentityMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleIdentityMismatch(IdentityMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
