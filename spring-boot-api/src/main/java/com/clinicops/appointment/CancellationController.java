package com.clinicops.appointment;

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

import java.util.UUID;

/**
 * Two endpoints, not one branching on role - their lookups are scoped
 * completely differently (tenant vs. ownership), same reasoning as the
 * reference project's CancellationController.
 */
@RestController
public class CancellationController {

    private final CancellationService cancellationService;
    private final CurrentUserService currentUserService;

    public CancellationController(CancellationService cancellationService, CurrentUserService currentUserService) {
        this.cancellationService = cancellationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/appointments/{id}/cancel")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Appointment cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelAppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID cancelledByUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;
        return cancellationService.cancel(id, TenantContext.require(), cancelledByUserId, reason);
    }

    @PostMapping("/api/my-appointments/{id}/cancel")
    @PreAuthorize("hasRole('PATIENT')")
    public Appointment cancelAsCustomer(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelAppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;
        return cancellationService.cancelAsCustomer(id, customerUserId, reason);
    }

    @ExceptionHandler(AppointmentAlreadyCancelledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyCancelled(AppointmentAlreadyCancelledException e) {
        return e.getMessage();
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(java.util.NoSuchElementException e) {
        return e.getMessage();
    }
}
