package com.clinicops.scheduling;

import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.provider.ProviderWorkingHours;
import com.clinicops.provider.ProviderWorkingHoursRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates {@link Slot} rows on demand from a provider's working hours -
 * lazy and idempotent, unlike the reference project's SeatLayoutGenerator/
 * TripCreationService (which generate all of a trip's seats once, at
 * creation): clinic availability is a rolling calendar, not a discrete
 * one-off event, so slots for a given day only need to exist once someone
 * actually asks about that day.
 */
@Service
public class SlotGenerationService {

    private final ProviderWorkingHoursRepository workingHoursRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final SlotRepository slotRepository;
    private final ClinicSettingsService clinicSettingsService;

    public SlotGenerationService(
            ProviderWorkingHoursRepository workingHoursRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            SlotRepository slotRepository,
            ClinicSettingsService clinicSettingsService) {
        this.workingHoursRepository = workingHoursRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.slotRepository = slotRepository;
        this.clinicSettingsService = clinicSettingsService;
    }

    /** Ensures every open-able slot from today through throughDate (inclusive) exists for this provider/appointment-type pair. */
    public void ensureSlotsGenerated(UUID tenantId, UUID providerId, UUID appointmentTypeId, LocalDate throughDate) {
        AppointmentType type = appointmentTypeRepository.findByIdAndTenantId(appointmentTypeId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + appointmentTypeId));

        ZoneId zone = clinicSettingsService.resolveTimezone(tenantId);
        Instant now = Instant.now();
        LocalDate date = LocalDate.now(zone);
        while (!date.isAfter(throughDate)) {
            generateForDay(tenantId, providerId, appointmentTypeId, type.getDurationMinutes(), date, now, zone);
            date = date.plusDays(1);
        }
    }

    /**
     * Ensures a slot exists at this exact instant, generating it if it
     * falls within the provider's working hours and doesn't already exist.
     * Used by recurring-series booking, where each occurrence needs one
     * specific instant rather than a whole browsable range. Empty if that
     * instant doesn't align with any working-hours window.
     */
    public Optional<Slot> ensureSlotExists(UUID tenantId, UUID providerId, UUID appointmentTypeId, Instant exactStartTime) {
        Optional<Slot> existing = slotRepository.findByProviderIdAndAppointmentTypeIdAndStartTime(
                providerId, appointmentTypeId, exactStartTime);
        if (existing.isPresent()) {
            return existing;
        }

        AppointmentType type = appointmentTypeRepository.findByIdAndTenantId(appointmentTypeId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + appointmentTypeId));
        ZoneId zone = clinicSettingsService.resolveTimezone(tenantId);
        LocalDate date = exactStartTime.atZone(zone).toLocalDate();
        List<ProviderWorkingHours> windows =
                workingHoursRepository.findAllByProviderIdAndDayOfWeek(providerId, SlotGenerator.dayOfWeek(date));

        boolean aligns = SlotGenerator.generateForDay(date, windows, type.getDurationMinutes(), Instant.EPOCH, zone).stream()
                .anyMatch(candidate -> candidate[0].equals(exactStartTime));
        if (!aligns) {
            return Optional.empty();
        }

        return Optional.of(saveSlotIfAbsent(
                tenantId, providerId, appointmentTypeId, exactStartTime,
                exactStartTime.plusSeconds(type.getDurationMinutes() * 60L)));
    }

    private void generateForDay(
            UUID tenantId, UUID providerId, UUID appointmentTypeId, int durationMinutes, LocalDate date, Instant notBefore, ZoneId zone) {
        List<ProviderWorkingHours> windows =
                workingHoursRepository.findAllByProviderIdAndDayOfWeek(providerId, SlotGenerator.dayOfWeek(date));
        if (windows.isEmpty()) {
            return;
        }
        for (Instant[] candidate : SlotGenerator.generateForDay(date, windows, durationMinutes, notBefore, zone)) {
            saveSlotIfAbsent(tenantId, providerId, appointmentTypeId, candidate[0], candidate[1]);
        }
    }

    private Slot saveSlotIfAbsent(UUID tenantId, UUID providerId, UUID appointmentTypeId, Instant start, Instant end) {
        Optional<Slot> existing = slotRepository.findByProviderIdAndAppointmentTypeIdAndStartTime(providerId, appointmentTypeId, start);
        if (existing.isPresent()) {
            return existing.get();
        }
        Slot slot = new Slot();
        slot.setTenantId(tenantId);
        slot.setProviderId(providerId);
        slot.setAppointmentTypeId(appointmentTypeId);
        slot.setStartTime(start);
        slot.setEndTime(end);
        try {
            return slotRepository.save(slot);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent generation call inserted the identical
            // (provider, appointment type, start_time) slot first -
            // idx_slots_provider_type_start lets exactly one win.
            return slotRepository.findByProviderIdAndAppointmentTypeIdAndStartTime(providerId, appointmentTypeId, start)
                    .orElseThrow(() -> e);
        }
    }
}
