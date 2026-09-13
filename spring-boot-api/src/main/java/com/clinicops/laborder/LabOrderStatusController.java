package com.clinicops.laborder;

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

    public LabOrderStatusController(LabOrderStatusService labOrderStatusService, CurrentUserService currentUserService) {
        this.labOrderStatusService = labOrderStatusService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/lab-orders/{id}/collect-specimen")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests collectSpecimen(@PathVariable UUID id, @RequestBody(required = false) CollectSpecimenRequest request) {
        String presentedIdNumber = request != null ? request.presentedIdNumber() : null;
        return labOrderStatusService.collectSpecimen(id, TenantContext.require(), presentedIdNumber);
    }

    @PostMapping("/api/lab-orders/{id}/send")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests send(@PathVariable UUID id) {
        return labOrderStatusService.send(id, TenantContext.require());
    }

    @PostMapping("/api/lab-orders/{id}/result")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests result(@PathVariable UUID id, @RequestBody ResultLabOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID resultedByUserId = currentUserService.resolveInternalUserId(jwt);
        return labOrderStatusService.result(id, TenantContext.require(), resultedByUserId, request);
    }

    @PostMapping("/api/lab-orders/{id}/review")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests review(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID reviewedByUserId = currentUserService.resolveInternalUserId(jwt);
        return labOrderStatusService.review(id, TenantContext.require(), reviewedByUserId);
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
