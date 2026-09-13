package com.clinicops.appointment;

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
public class RescheduleController {

    private final RescheduleService rescheduleService;
    private final CurrentUserService currentUserService;

    public RescheduleController(RescheduleService rescheduleService, CurrentUserService currentUserService) {
        this.rescheduleService = rescheduleService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/appointments/{id}/reschedule")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Appointment reschedule(
            @PathVariable UUID id,
            @Valid @RequestBody RescheduleAppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actingUserId = currentUserService.resolveInternalUserId(jwt);
        return rescheduleService.reschedule(
                id, TenantContext.require(), request.newSlotId(), request.newProviderId(), actingUserId);
    }

    @PostMapping("/api/my-appointments/{id}/reschedule")
    @PreAuthorize("hasRole('PATIENT')")
    public Appointment rescheduleAsCustomer(
            @PathVariable UUID id,
            @Valid @RequestBody RescheduleAppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return rescheduleService.rescheduleAsCustomer(id, customerUserId, request.newSlotId(), request.newProviderId());
    }

    @ExceptionHandler(AppointmentAlreadyCancelledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyCancelled(AppointmentAlreadyCancelledException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TooLateToRescheduleException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleTooLate(TooLateToRescheduleException e) {
        return e.getMessage();
    }

    @ExceptionHandler(SlotConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleSlotConflict(SlotConflictException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TenantMismatchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleTenantMismatch(TenantMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
