package com.clinicops.imaging;

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
 * Clinical content - create/update are provider+clinic_admin only, same
 * reasoning LabOrderController already applies (ordering is a clinical
 * decision, not a technologist's job). Read is widened to
 * imaging_technologist (needs to see what's ordered to perform it),
 * mirroring LabOrderController's own lab_technician read-widening exactly.
 */
@RestController
public class ImagingOrderController {

    private final ImagingOrderService imagingOrderService;
    private final PhiAccessAuditService phiAccessAuditService;

    public ImagingOrderController(ImagingOrderService imagingOrderService, PhiAccessAuditService phiAccessAuditService) {
        this.imagingOrderService = imagingOrderService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/imaging-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public ImagingOrder createImagingOrder(@Valid @RequestBody CreateImagingOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderService.create(tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", order.getId(), order.getPatientId(), "/api/imaging-orders");
        return order;
    }

    @GetMapping("/api/imaging-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'IMAGING_TECHNOLOGIST')")
    public List<ImagingOrder> imagingOrders(@AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        phiAccessAuditService.logRead(tenantId, jwt, "imaging_order_list", null, null, "/api/imaging-orders");
        return imagingOrderService.listForTenant(tenantId);
    }

    @GetMapping("/api/imaging-orders/{id}")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'IMAGING_TECHNOLOGIST')")
    public ImagingOrder imagingOrder(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderService.get(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}");
        return order;
    }

    @PostMapping("/api/imaging-orders/{id}/update")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public ImagingOrder updateImagingOrder(@PathVariable UUID id, @RequestBody UpdateImagingOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        ImagingOrder order = imagingOrderService.update(id, tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "imaging_order", id, order.getPatientId(), "/api/imaging-orders/{id}/update");
        return order;
    }

    @ExceptionHandler(NoImagingRateConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleNoRateConfigured(NoImagingRateConfiguredException e) {
        return e.getMessage();
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
