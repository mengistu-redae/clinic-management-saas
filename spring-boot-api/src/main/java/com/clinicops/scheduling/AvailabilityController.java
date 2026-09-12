package com.clinicops.scheduling;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Public availability browsing - serves both an anonymous guest and a
 * logged-in patient alike (see SecurityConfig's permitAll() list), same as
 * the reference project's marketplace trip search. Unlike that search,
 * this is scoped to one clinic the caller already picked (GET /api/clinics)
 * rather than cross-tenant - clinics define their own appointment_types
 * independently, with no shared cross-clinic identity to search by.
 */
@RestController
public class AvailabilityController {

    /** Default browsing horizon when the caller doesn't specify `to` - matches SlotGenerationService's lazy-generation model. */
    private static final int DEFAULT_HORIZON_DAYS = 14;

    private final SlotGenerationService slotGenerationService;
    private final SlotRepository slotRepository;

    public AvailabilityController(SlotGenerationService slotGenerationService, SlotRepository slotRepository) {
        this.slotGenerationService = slotGenerationService;
        this.slotRepository = slotRepository;
    }

    @GetMapping("/api/clinics/{clinicId}/availability")
    public List<AvailableSlotView> availability(
            @PathVariable UUID clinicId,
            @RequestParam UUID providerId,
            @RequestParam UUID appointmentTypeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate throughDate = to != null ? to : LocalDate.now(ZoneOffset.UTC).plusDays(DEFAULT_HORIZON_DAYS);
        slotGenerationService.ensureSlotsGenerated(clinicId, providerId, appointmentTypeId, throughDate);

        Instant from = Instant.now();
        Instant until = throughDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return slotRepository
                .findAllByTenantIdAndProviderIdAndAppointmentTypeIdAndStatusAndStartTimeBetween(
                        clinicId, providerId, appointmentTypeId, "open", from, until)
                .stream()
                .map(AvailableSlotView::from)
                .toList();
    }
}
