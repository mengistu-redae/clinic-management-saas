package com.clinicops.appointment;

import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * booked -> checked_in -> roomed -> with_provider -> checked_out, plus
 * no_show (only reachable from booked). A fresh design - no direct
 * reference-project analog beyond the shared conventions: re-calling a
 * transition already reached is idempotent (returns the current state, no
 * re-validation), calling one out of order throws
 * {@link InvalidAppointmentStatusException} (409).
 */
@Service
public class CheckInService {

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final PatientRepository patientRepository;

    public CheckInService(
            AppointmentRepository appointmentRepository,
            SlotRepository slotRepository,
            PatientRepository patientRepository) {
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.patientRepository = patientRepository;
    }

    /**
     * The one transition with extra validation beyond the plain state
     * machine: the slot's window must not have fully elapsed yet (checked
     * live against the slot's own end time, never a stored/scheduler-set
     * status - see NoShowScheduler), and an optional presented ID is
     * checked against the patient's ID on file. No ID on file -> allowed
     * through (decided in plan mode) - only an actual mismatch throws.
     */
    @Transactional
    public Appointment checkIn(UUID appointmentId, UUID tenantId, String presentedIdNumber) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));

        if ("checked_in".equals(appointment.getStatus())) {
            return appointment; // idempotent re-call
        }
        if (!"booked".equals(appointment.getStatus())) {
            throw new InvalidAppointmentStatusException(
                    "Cannot check in an appointment with status '" + appointment.getStatus() + "'");
        }

        Slot slot = slotRepository.findById(appointment.getSlotId())
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + appointment.getSlotId()));
        if (Instant.now().isAfter(slot.getEndTime())) {
            throw new CheckInWindowClosedException(
                    "This appointment's time window has already passed: " + appointmentId);
        }

        if (presentedIdNumber != null && !presentedIdNumber.isBlank() && appointment.getPatientId() != null) {
            String onFile = patientRepository.findById(appointment.getPatientId())
                    .map(Patient::getNationalId)
                    .orElse(null);
            // No ID on file -> nothing to contradict, allowed through. Only
            // a genuine value-vs-value mismatch is rejected.
            if (onFile != null && !onFile.isBlank() && !onFile.equals(presentedIdNumber)) {
                throw new IdentityMismatchException("Presented ID does not match the ID on file");
            }
        }

        appointment.setStatus("checked_in");
        return appointmentRepository.save(appointment);
    }

    @Transactional
    public Appointment room(UUID appointmentId, UUID tenantId) {
        return applyTransition(appointmentId, tenantId, "checked_in", "roomed", "room");
    }

    @Transactional
    public Appointment start(UUID appointmentId, UUID tenantId) {
        return applyTransition(appointmentId, tenantId, "roomed", "with_provider", "start");
    }

    @Transactional
    public Appointment checkOut(UUID appointmentId, UUID tenantId) {
        return applyTransition(appointmentId, tenantId, "with_provider", "checked_out", "check out");
    }

    @Transactional
    public Appointment markNoShow(UUID appointmentId, UUID tenantId) {
        return applyTransition(appointmentId, tenantId, "booked", "no_show", "mark as no-show");
    }

    private Appointment applyTransition(
            UUID appointmentId, UUID tenantId, String fromStatus, String toStatus, String transitionName) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));

        if (toStatus.equals(appointment.getStatus())) {
            return appointment; // idempotent re-call
        }
        if (!fromStatus.equals(appointment.getStatus())) {
            throw new InvalidAppointmentStatusException(
                    "Cannot " + transitionName + " an appointment with status '" + appointment.getStatus() + "'");
        }

        appointment.setStatus(toStatus);
        return appointmentRepository.save(appointment);
    }
}
