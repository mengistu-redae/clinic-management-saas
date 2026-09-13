package com.clinicops.provider;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Nested under the provider resource - a provider isn't manageable without editable working hours. */
@RestController
public class ProviderWorkingHoursController {

    private final ProviderRepository providerRepository;
    private final ProviderWorkingHoursRepository workingHoursRepository;

    public ProviderWorkingHoursController(ProviderRepository providerRepository, ProviderWorkingHoursRepository workingHoursRepository) {
        this.providerRepository = providerRepository;
        this.workingHoursRepository = workingHoursRepository;
    }

    @GetMapping("/api/providers/{providerId}/working-hours")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<ProviderWorkingHours> workingHours(@PathVariable UUID providerId) {
        requireOwnedProvider(providerId, TenantContext.require());
        return workingHoursRepository.findAllByProviderId(providerId);
    }

    /**
     * Rejects a window that overlaps an existing one for the same
     * provider+day (checked in-memory, not a DB constraint - the schema
     * has none) rather than allowing a nonsensical double-booked window.
     */
    @PostMapping("/api/providers/{providerId}/working-hours")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ProviderWorkingHours createWorkingHours(@PathVariable UUID providerId, @Valid @RequestBody CreateWorkingHoursRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedProvider(providerId, tenantId);

        if (!request.startTime().isBefore(request.endTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }
        short dayOfWeek = request.dayOfWeek().shortValue();
        boolean overlaps = workingHoursRepository.findAllByProviderIdAndDayOfWeek(providerId, dayOfWeek).stream()
                .anyMatch(existing -> existing.getStartTime().isBefore(request.endTime())
                        && request.startTime().isBefore(existing.getEndTime()));
        if (overlaps) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This window overlaps an existing one for this provider and day");
        }

        ProviderWorkingHours hours = new ProviderWorkingHours();
        hours.setTenantId(tenantId);
        hours.setProviderId(providerId);
        hours.setDayOfWeek(dayOfWeek);
        hours.setStartTime(request.startTime());
        hours.setEndTime(request.endTime());
        return workingHoursRepository.save(hours);
    }

    /** Hard delete - no FK references a working-hours row directly, and already-generated slots don't cascade from it. */
    @PostMapping("/api/providers/{providerId}/working-hours/{id}/remove")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ProviderWorkingHours removeWorkingHours(@PathVariable UUID providerId, @PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedProvider(providerId, tenantId);
        ProviderWorkingHours hours = workingHoursRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Working hours row not found: " + id));
        if (!hours.getProviderId().equals(providerId)) {
            throw new NoSuchElementException("Working hours row not found: " + id);
        }
        workingHoursRepository.delete(hours);
        return hours;
    }

    private void requireOwnedProvider(UUID providerId, UUID tenantId) {
        providerRepository.findByIdAndTenantId(providerId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + providerId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
