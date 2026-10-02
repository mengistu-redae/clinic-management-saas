package com.clinicops.laborder;

import com.clinicops.labrate.NoLabRateConfiguredException;
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

/**
 * Clinical content - provider + clinic_admin only, no front_desk access,
 * same reasoning phase 4 already applied to encounters.
 */
@RestController
public class LabOrderController {

    private final LabOrderService labOrderService;
    private final PhiAccessAuditService phiAccessAuditService;

    public LabOrderController(LabOrderService labOrderService, PhiAccessAuditService phiAccessAuditService) {
        this.labOrderService = labOrderService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/lab-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests createLabOrder(@Valid @RequestBody CreateLabOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderService.create(tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", result.order().getId(), result.order().getPatientId(), "/api/lab-orders");
        return result;
    }

    /**
     * One log row per list call, not one per order - same "a list touches many patients at once" reasoning as PatientController.patients.
     * Read access widened to lab_technician (2026-10-02, lab module L1) - a lab tech needs to see what's ordered to process it; write access
     * (create/update/cancel/confirm-and-order below) stays provider+clinic_admin only, ordering remains a clinical decision.
     */
    @GetMapping("/api/lab-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'LAB_TECHNICIAN')")
    public List<LabOrder> labOrders(@AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        phiAccessAuditService.logRead(tenantId, jwt, "lab_order_list", null, null, "/api/lab-orders");
        return labOrderService.listForTenant(tenantId);
    }

    @GetMapping("/api/lab-orders/{id}")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'LAB_TECHNICIAN')")
    public LabOrderWithTests labOrder(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderService.get(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}");
        return result;
    }

    @PostMapping("/api/lab-orders/{id}/update")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests updateLabOrder(@PathVariable UUID id, @RequestBody UpdateLabOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderService.update(id, tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/update");
        return result;
    }

    /** Two-factor public tracking - ref + the patient's own phone must match, otherwise 404 identically to an unknown ref. Status/timestamps only - see LabOrderTrackingView. */
    @GetMapping("/api/lab-orders/track/{orderRef}")
    public LabOrderTrackingView trackLabOrder(@PathVariable String orderRef, @RequestParam String phone) {
        return labOrderService.trackByRefAndPhone(orderRef, phone);
    }

    @ExceptionHandler(EncounterPatientMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleEncounterPatientMismatch(EncounterPatientMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(RestrictedTestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleRestrictedTest(RestrictedTestException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidLabOrderTestsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidTests(InvalidLabOrderTestsException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoLabRateConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleNoRateConfigured(NoLabRateConfiguredException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidLabOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidLabOrderStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
