package com.clinicops.vitals;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.InvalidAppointmentStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - same "not a contended
 * resource" reasoning as EncounterService, last-write-wins is fine here
 * too. No ownership check the way Encounter has one (a specific provider
 * "owns" the chart) - vitals are recordable by any of provider/clinic_admin/
 * front_desk regardless of which provider the appointment is assigned to,
 * since front-desk/nursing-style intake isn't provider-specific.
 */
@Service
public class VitalsService {

    private final VitalsRepository vitalsRepository;
    private final AppointmentRepository appointmentRepository;

    public VitalsService(VitalsRepository vitalsRepository, AppointmentRepository appointmentRepository) {
        this.vitalsRepository = vitalsRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Creates on first call, updates on every later call - appointmentId is
     * unique at the DB level, so there's exactly one row per appointment
     * either way, same shape as EncounterService.upsert. No status gate
     * beyond "not cancelled" - vitals are typically taken at check-in/
     * roomed, well before EncounterService's own with_provider/checked_out
     * gate would allow a clinical note to even exist yet.
     */
    @Transactional
    public Vitals upsert(UUID appointmentId, UUID tenantId, UUID recordedByUserId, UpsertVitalsRequest request) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        if ("cancelled".equals(appointment.getStatus())) {
            throw new InvalidAppointmentStatusException("Cannot record vitals for a cancelled appointment: " + appointmentId);
        }
        if (request.painScore() != null && (request.painScore() < 0 || request.painScore() > 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "painScore must be between 0 and 10");
        }

        Vitals vitals = vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseGet(() -> {
                    Vitals fresh = new Vitals();
                    fresh.setTenantId(tenantId);
                    fresh.setAppointmentId(appointmentId);
                    return fresh;
                });
        vitals.setHeightCm(request.heightCm());
        vitals.setWeightKg(request.weightKg());
        vitals.setTemperatureC(request.temperatureC());
        vitals.setPulseBpm(request.pulseBpm());
        vitals.setRespiratoryRate(request.respiratoryRate());
        vitals.setBloodPressureSystolic(request.bloodPressureSystolic());
        vitals.setBloodPressureDiastolic(request.bloodPressureDiastolic());
        vitals.setOxygenSaturationPct(request.oxygenSaturationPct());
        vitals.setPainScore(request.painScore());
        vitals.setRecordedBy(recordedByUserId);
        vitals.setUpdatedAt(Instant.now());
        return vitalsRepository.save(vitals);
    }

    @Transactional(readOnly = true)
    public Vitals get(UUID appointmentId, UUID tenantId) {
        return vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No vitals recorded yet for appointment: " + appointmentId));
    }
}
