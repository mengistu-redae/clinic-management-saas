package com.clinicops.encounter;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.InvalidAppointmentStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - unlike booking/reschedule,
 * an encounter isn't a contended resource (no Redis lock needed): two
 * concurrent saves on the same appointment just last-write-win, which is
 * fine given the "editable indefinitely" decision.
 */
@Service
public class EncounterService {

    /** Encounter documentation is only allowed once the provider has actually started seeing the patient. */
    private static final Set<String> DOCUMENTABLE_STATUSES = Set.of("with_provider", "checked_out");

    private final EncounterRepository encounterRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;

    public EncounterService(
            EncounterRepository encounterRepository,
            PrescriptionRepository prescriptionRepository,
            AppointmentRepository appointmentRepository) {
        this.encounterRepository = encounterRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Creates the encounter on first call, updates it on every later call -
     * appointment_id is unique at the DB level, so there's exactly one row
     * per appointment either way. {@code actingProviderId} null means the
     * caller is clinic_admin (no ownership check); otherwise it must match
     * the appointment's own provider.
     */
    @Transactional
    public Encounter upsert(
            UUID appointmentId, UUID tenantId, UUID actingProviderId,
            String chiefComplaint, String assessment, String plan) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        requireDocumentableStatus(appointment);
        requireOwnership(appointment, actingProviderId);

        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseGet(() -> {
                    Encounter fresh = new Encounter();
                    fresh.setTenantId(tenantId);
                    fresh.setAppointmentId(appointmentId);
                    fresh.setProviderId(appointment.getProviderId());
                    return fresh;
                });
        encounter.setChiefComplaint(chiefComplaint);
        encounter.setAssessment(assessment);
        encounter.setPlan(plan);
        encounter.setUpdatedAt(Instant.now());
        return encounterRepository.save(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterWithPrescriptions get(UUID appointmentId, UUID tenantId) {
        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No encounter documented yet for appointment: " + appointmentId));
        return new EncounterWithPrescriptions(encounter, prescriptionRepository.findAllByEncounterId(encounter.getId()));
    }

    /** Full-replace semantics - the given list becomes the entire prescription list, never merged with what was there before. */
    @Transactional
    public List<Prescription> replacePrescriptions(
            UUID appointmentId, UUID tenantId, UUID actingProviderId, List<PrescriptionInput> items) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        requireDocumentableStatus(appointment);
        requireOwnership(appointment, actingProviderId);

        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Create the encounter before adding prescriptions"));

        prescriptionRepository.deleteAllByEncounterId(encounter.getId());
        return items.stream()
                .map(item -> {
                    Prescription prescription = new Prescription();
                    prescription.setTenantId(tenantId);
                    prescription.setEncounterId(encounter.getId());
                    prescription.setMedicationName(item.medicationName());
                    prescription.setDosage(item.dosage());
                    prescription.setInstructions(item.instructions());
                    return prescriptionRepository.save(prescription);
                })
                .toList();
    }

    private void requireDocumentableStatus(Appointment appointment) {
        if (!DOCUMENTABLE_STATUSES.contains(appointment.getStatus())) {
            throw new InvalidAppointmentStatusException(
                    "Cannot document an encounter for an appointment with status '" + appointment.getStatus() + "'");
        }
    }

    private void requireOwnership(Appointment appointment, UUID actingProviderId) {
        if (actingProviderId != null && !actingProviderId.equals(appointment.getProviderId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "This appointment is assigned to a different provider");
        }
    }
}
