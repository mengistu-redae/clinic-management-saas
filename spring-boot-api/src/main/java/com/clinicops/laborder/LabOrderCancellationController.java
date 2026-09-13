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
public class LabOrderCancellationController {

    private final LabOrderCancellationService labOrderCancellationService;
    private final CurrentUserService currentUserService;

    public LabOrderCancellationController(LabOrderCancellationService labOrderCancellationService, CurrentUserService currentUserService) {
        this.labOrderCancellationService = labOrderCancellationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/lab-orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests cancel(@PathVariable UUID id, @RequestBody(required = false) CancelLabOrderRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID cancelledByUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;
        return labOrderCancellationService.cancel(id, TenantContext.require(), cancelledByUserId, reason);
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
