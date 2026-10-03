package com.clinicops.imaging;

import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
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
public class ImagingOrderStatusController {

    private final ImagingOrderStatusService imagingOrderStatusService;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public ImagingOrderStatusController(
            ImagingOrderStatusService imagingOrderStatusService, CurrentUserService currentUserService, PhiAccessAuditService phiAccessAuditService) {
        this.imagingOrderStatusService = imagingOrderStatusService;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    /** imaging_technologist+clinic_admin own schedule/start/complete - purely mechanical, mirrors the lab_technician split exactly. */
    @PostMapping("/api/imaging-orders/{id}/schedule")
    @PreAuthorize("hasAnyRole('IMAGING_TECHNOLOGIST', 'CLINIC_ADMIN')")
    public ImagingOrder schedule(@PathVariable UUID id, @RequestBody(required = false) ScheduleImagingOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.schedule(id, tenantId, request != null ? request : new ScheduleImagingOrderRequest(null));
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/schedule");
        return order;
    }

    @PostMapping("/api/imaging-orders/{id}/start")
    @PreAuthorize("hasAnyRole('IMAGING_TECHNOLOGIST', 'CLINIC_ADMIN')")
    public ImagingOrder start(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.start(id, tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/start");
        return order;
    }

    @PostMapping("/api/imaging-orders/{id}/complete")
    @PreAuthorize("hasAnyRole('IMAGING_TECHNOLOGIST', 'CLINIC_ADMIN')")
    public ImagingOrder complete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.complete(id, tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/complete");
        return order;
    }

    /** Writes the report and signs off in one step - provider+clinic_admin only, the actual clinical judgment. */
    @PostMapping("/api/imaging-orders/{id}/review")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public ImagingOrder review(@PathVariable UUID id, @Valid @RequestBody ReviewImagingOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID reviewedByUserId = currentUserService.resolveInternalUserId(jwt);
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.review(id, tenantId, reviewedByUserId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/review");
        return order;
    }

    @PostMapping("/api/imaging-orders/{id}/acknowledge-critical")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public ImagingOrder acknowledgeCritical(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID acknowledgedByUserId = currentUserService.resolveInternalUserId(jwt);
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.acknowledgeCritical(id, tenantId, acknowledgedByUserId);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/acknowledge-critical");
        return order;
    }

    @PostMapping("/api/imaging-orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public ImagingOrder cancel(@PathVariable UUID id, @RequestBody(required = false) CancelImagingOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderStatusService.cancel(id, tenantId, request != null ? request : new CancelImagingOrderRequest(null));
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/cancel");
        return order;
    }

    @ExceptionHandler(InvalidImagingOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidImagingOrderStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
