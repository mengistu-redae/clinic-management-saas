package com.clinicops.appointment;

import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/api/appointments/{id}/check-in")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment checkIn(@PathVariable UUID id, @RequestBody(required = false) CheckInRequest request) {
        String presentedIdNumber = request != null ? request.presentedIdNumber() : null;
        return checkInService.checkIn(id, TenantContext.require(), presentedIdNumber);
    }

    @PostMapping("/api/appointments/{id}/room")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment room(@PathVariable UUID id) {
        return checkInService.room(id, TenantContext.require());
    }

    @PostMapping("/api/appointments/{id}/start")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment start(@PathVariable UUID id) {
        return checkInService.start(id, TenantContext.require());
    }

    @PostMapping("/api/appointments/{id}/check-out")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment checkOut(@PathVariable UUID id) {
        return checkInService.checkOut(id, TenantContext.require());
    }

    @PostMapping("/api/appointments/{id}/no-show")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment noShow(@PathVariable UUID id) {
        return checkInService.markNoShow(id, TenantContext.require());
    }

    @ExceptionHandler(InvalidAppointmentStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidAppointmentStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(CheckInWindowClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleWindowClosed(CheckInWindowClosedException e) {
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
