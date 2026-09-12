package com.clinicops.appointment;

import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.scheduling.SlotGenerationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Books a bounded recurring series (e.g. "every 2 weeks x6") - staff-only,
 * a deliberate expansion of phase 2's original scope decided in plan mode.
 *
 * Deliberately reuses the exact single-slot lock+write primitive
 * ({@link AppointmentService#createAppointment}) in a loop, one occurrence
 * at a time - no new locking mechanism. Each occurrence gets a
 * per-occurrence idempotency key ({@code "<request key>::<index>"}) so
 * retrying the whole series request is itself idempotent: an
 * already-created occurrence is returned as-is (via
 * AppointmentService's own idempotency check), and a previously-conflicted
 * occurrence is retried fresh.
 */
@Service
public class AppointmentSeriesService {

    private final AppointmentSeriesRepository seriesRepository;
    private final AppointmentService appointmentService;
    private final SlotGenerationService slotGenerationService;
    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final int maxOccurrences;

    public AppointmentSeriesService(
            AppointmentSeriesRepository seriesRepository,
            AppointmentService appointmentService,
            SlotGenerationService slotGenerationService,
            PatientRepository patientRepository,
            ProviderRepository providerRepository,
            @Value("${clinicops.appointment.series.max-occurrences}") int maxOccurrences) {
        this.seriesRepository = seriesRepository;
        this.appointmentService = appointmentService;
        this.slotGenerationService = slotGenerationService;
        this.patientRepository = patientRepository;
        this.providerRepository = providerRepository;
        this.maxOccurrences = maxOccurrences;
    }

    public AppointmentSeriesResult createSeries(CreateAppointmentSeriesRequest request, UUID tenantId, String recipientEmail) {
        if (request.occurrenceCount() > maxOccurrences) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "occurrenceCount exceeds the maximum of " + maxOccurrences);
        }
        patientRepository.findByIdAndTenantId(request.patientId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + request.patientId()));
        providerRepository.findByIdAndTenantId(request.providerId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + request.providerId()));

        AppointmentSeries series = seriesRepository.findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                .orElseGet(() -> {
                    AppointmentSeries newSeries = new AppointmentSeries();
                    newSeries.setTenantId(tenantId);
                    newSeries.setPatientId(request.patientId());
                    newSeries.setProviderId(request.providerId());
                    newSeries.setAppointmentTypeId(request.appointmentTypeId());
                    newSeries.setChannel("front_desk");
                    newSeries.setIntervalWeeks(request.intervalWeeks());
                    newSeries.setOccurrenceCount(request.occurrenceCount());
                    newSeries.setIdempotencyKey(request.idempotencyKey());
                    return seriesRepository.save(newSeries);
                });

        List<Appointment> created = new ArrayList<>();
        List<AppointmentSeriesResult.Conflict> conflicts = new ArrayList<>();

        for (int occurrenceIndex = 0; occurrenceIndex < request.occurrenceCount(); occurrenceIndex++) {
            Instant occurrenceStart = request.firstOccurrenceStart()
                    .plus(Duration.ofDays(7L * request.intervalWeeks() * occurrenceIndex));

            Optional<com.clinicops.scheduling.Slot> slot = slotGenerationService.ensureSlotExists(
                    tenantId, request.providerId(), request.appointmentTypeId(), occurrenceStart);
            if (slot.isEmpty()) {
                conflicts.add(new AppointmentSeriesResult.Conflict(
                        occurrenceIndex, occurrenceStart, "No working-hours slot at this time"));
                continue;
            }

            var command = new AppointmentBookingCommand(
                    slot.get().getId(), request.providerId(), request.appointmentTypeId(), "front_desk",
                    tenantId, request.patientId(), null, null, null, recipientEmail,
                    request.idempotencyKey() + "::" + occurrenceIndex, series.getId(), occurrenceIndex);
            try {
                created.add(appointmentService.createAppointment(command));
            } catch (SlotConflictException e) {
                conflicts.add(new AppointmentSeriesResult.Conflict(occurrenceIndex, occurrenceStart, "Slot already booked"));
            }
        }

        return new AppointmentSeriesResult(series, created, conflicts);
    }
}
