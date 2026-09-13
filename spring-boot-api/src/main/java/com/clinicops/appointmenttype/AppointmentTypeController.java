package com.clinicops.appointmenttype;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@RestController
public class AppointmentTypeController {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final AppointmentTypeRepository appointmentTypeRepository;

    public AppointmentTypeController(AppointmentTypeRepository appointmentTypeRepository) {
        this.appointmentTypeRepository = appointmentTypeRepository;
    }

    @GetMapping("/api/appointment-types")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<AppointmentType> appointmentTypes(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? appointmentTypeRepository.findAllByTenantId(tenantId)
                : appointmentTypeRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/appointment-types/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public AppointmentType appointmentType(@PathVariable UUID id) {
        return appointmentTypeRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + id));
    }

    @PostMapping("/api/appointment-types")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public AppointmentType createAppointmentType(@Valid @RequestBody CreateAppointmentTypeRequest request) {
        AppointmentType type = new AppointmentType();
        type.setTenantId(TenantContext.require());
        type.setName(request.name());
        type.setDurationMinutes(request.durationMinutes());
        type.setPriceAmount(request.priceAmount());
        return appointmentTypeRepository.save(type);
    }

    @PostMapping("/api/appointment-types/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public AppointmentType updateAppointmentType(@PathVariable UUID id, @RequestBody UpdateAppointmentTypeRequest request) {
        AppointmentType type = appointmentTypeRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + id));
        if (request.name() != null) {
            type.setName(request.name());
        }
        if (request.durationMinutes() != null) {
            if (request.durationMinutes() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durationMinutes must be positive");
            }
            type.setDurationMinutes(request.durationMinutes());
        }
        if (request.priceAmount() != null) {
            if (request.priceAmount().signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priceAmount must not be negative");
            }
            type.setPriceAmount(request.priceAmount());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            type.setStatus(request.status());
        }
        return appointmentTypeRepository.save(type);
    }

    /**
     * Public - a patient/guest needs some way to discover a clinic's
     * appointment types before booking one, since POST /api/appointments
     * already requires an appointmentTypeId. Mirrors AvailabilityController's
     * permitAll shape. Active only - inactive types aren't offered for
     * new bookings.
     */
    @GetMapping("/api/clinics/{clinicId}/appointment-types")
    public List<AppointmentTypeDirectoryView> publicAppointmentTypes(@PathVariable UUID clinicId) {
        return appointmentTypeRepository.findAllByTenantIdAndStatus(clinicId, "active").stream()
                .map(AppointmentTypeDirectoryView::from)
                .toList();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
