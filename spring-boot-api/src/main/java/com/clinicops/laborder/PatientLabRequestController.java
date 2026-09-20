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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** The patient-initiated request -> staff confirm-and-order two-phase flow - a specimen has to be physically collected, so a patient can't issue a priced order themselves. */
@RestController
public class PatientLabRequestController {

    private final LabOrderService labOrderService;
    private final LabOrderRepository labOrderRepository;
    private final PhiAccessAuditService phiAccessAuditService;

    public PatientLabRequestController(
            LabOrderService labOrderService, LabOrderRepository labOrderRepository, PhiAccessAuditService phiAccessAuditService) {
        this.labOrderService = labOrderService;
        this.labOrderRepository = labOrderRepository;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @PostMapping("/api/my-lab-orders")
    @PreAuthorize("hasRole('PATIENT')")
    public LabOrderWithTests createRequest(@Valid @RequestBody CreateLabRequestRequest request, @AuthenticationPrincipal Jwt jwt) {
        return labOrderService.createRequest(jwt, request);
    }

    @GetMapping("/api/my-lab-orders")
    @PreAuthorize("hasRole('PATIENT')")
    public List<LabOrderWithTests> myLabOrders(@AuthenticationPrincipal Jwt jwt) {
        return labOrderService.myLabOrders(jwt);
    }

    /** Staff review queue - pending patient-initiated requests for this clinic. */
    @GetMapping("/api/lab-orders/requests")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<LabOrder> requests() {
        return labOrderRepository.findAllByTenantIdAndStatus(TenantContext.require(), "requested");
    }

    @PostMapping("/api/lab-orders/{id}/confirm-and-order")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests confirmAndOrder(@PathVariable UUID id, @Valid @RequestBody ConfirmAndOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        LabOrderWithTests result = labOrderService.confirmAndOrder(id, tenantId, request);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order", id, result.order().getPatientId(), "/api/lab-orders/{id}/confirm-and-order");
        return result;
    }

    @ExceptionHandler(RequestNotIssuableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleRequestNotIssuable(RequestNotIssuableException e) {
        return e.getMessage();
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

    @ExceptionHandler(NoLabRateConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleNoRateConfigured(NoLabRateConfiguredException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
