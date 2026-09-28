package com.clinicops.encounter;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.InvalidAppointmentStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - unlike booking/reschedule,
 * an encounter isn't a contended resource (no Redis lock needed): two
 * concurrent saves on the same appointment just last-write-win, which is
 * fine given the "editable indefinitely" decision (until signed - see
 * Encounter's own javadoc for the phase-12 sign-and-lock behavior).
 */
@Service
public class EncounterService {

    /** Encounter documentation is only allowed once the provider has actually started seeing the patient. */
    private static final Set<String> DOCUMENTABLE_STATUSES = Set.of("with_provider", "checked_out");

    /** Phase 11 - kept intentionally short of a full formulary's route vocabulary; "other" is the escape hatch. */
    private static final Set<String> VALID_ROUTES =
            Set.of("oral", "iv", "im", "subcutaneous", "topical", "inhaled", "rectal", "sublingual", "other");

    private static final Set<String> VALID_PRESCRIPTION_STATUSES = Set.of("active", "completed", "discontinued");

    private final EncounterRepository encounterRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterAddendumRepository encounterAddendumRepository;

    public EncounterService(
            EncounterRepository encounterRepository,
            PrescriptionRepository prescriptionRepository,
            AppointmentRepository appointmentRepository,
            EncounterAddendumRepository encounterAddendumRepository) {
        this.encounterRepository = encounterRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.appointmentRepository = appointmentRepository;
        this.encounterAddendumRepository = encounterAddendumRepository;
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
            String chiefComplaint, String assessment, String plan, String icd10Codes) {
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
        requireUnlocked(encounter);
        encounter.setChiefComplaint(chiefComplaint);
        encounter.setAssessment(assessment);
        encounter.setPlan(plan);
        encounter.setIcd10Codes(icd10Codes);
        encounter.setUpdatedAt(Instant.now());
        return encounterRepository.save(encounter);
    }

    @Transactional(readOnly = true)
    public EncounterWithPrescriptions get(UUID appointmentId, UUID tenantId) {
        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No encounter documented yet for appointment: " + appointmentId));
        return new EncounterWithPrescriptions(
                encounter,
                prescriptionRepository.findAllByEncounterId(encounter.getId()),
                encounterAddendumRepository.findAllByEncounterIdOrderByCreatedAtAsc(encounter.getId()));
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
        requireUnlocked(encounter);

        for (PrescriptionInput item : items) {
            if (item.route() != null && !VALID_ROUTES.contains(item.route())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "route must be one of " + VALID_ROUTES);
            }
            if (item.status() != null && !VALID_PRESCRIPTION_STATUSES.contains(item.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_PRESCRIPTION_STATUSES);
            }
        }

        prescriptionRepository.deleteAllByEncounterId(encounter.getId());
        return items.stream()
                .map(item -> {
                    Prescription prescription = new Prescription();
                    prescription.setTenantId(tenantId);
                    prescription.setEncounterId(encounter.getId());
                    prescription.setMedicationName(item.medicationName());
                    prescription.setDosage(item.dosage());
                    prescription.setInstructions(item.instructions());
                    prescription.setRoute(item.route());
                    prescription.setFrequency(item.frequency());
                    prescription.setDuration(item.duration());
                    prescription.setQuantityDispensed(item.quantityDispensed());
                    prescription.setRefillsAllowed(item.refillsAllowed());
                    prescription.setStatus(item.status() != null ? item.status() : "active");
                    return prescriptionRepository.save(prescription);
                })
                .toList();
    }

    /**
     * Idempotent re-call (already-signed returns the current row unchanged,
     * same convention as CheckInService's own transitions) - no unsign/
     * reopen endpoint exists anywhere, once signed always signed.
     */
    @Transactional
    public Encounter sign(UUID appointmentId, UUID tenantId, UUID actingProviderId, UUID signedByUserId) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        requireDocumentableStatus(appointment);
        requireOwnership(appointment, actingProviderId);

        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Create the encounter before signing it"));

        if (encounter.getSignedAt() != null) {
            return encounter; // idempotent re-call
        }
        // Truncated to microseconds - Postgres' own storage precision - so
        // this first response's signedAt matches byte-for-byte what a later
        // read of the same row returns, rather than momentarily showing
        // extra nanosecond digits that get silently dropped on persist.
        encounter.setSignedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        encounter.setSignedBy(signedByUserId);
        return encounterRepository.save(encounter);
    }

    /**
     * Only allowed once the encounter is signed - before that, a provider
     * just edits it directly via the normal upsert, there's no reason to
     * addend something still editable. Deliberately does NOT re-check
     * {@link #requireDocumentableStatus} - an addendum is a correction to
     * an already-finalized note, made regardless of what the appointment's
     * own status has done since (e.g. a later cancellation shouldn't block
     * correcting an already-signed note).
     */
    @Transactional
    public EncounterAddendum addAddendum(UUID appointmentId, UUID tenantId, UUID actingProviderId, UUID authorUserId, String text) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        requireOwnership(appointment, actingProviderId);

        Encounter encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Create the encounter before adding an addendum"));
        if (encounter.getSignedAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Cannot add an addendum before the encounter is signed - edit it directly instead");
        }

        EncounterAddendum addendum = new EncounterAddendum();
        addendum.setTenantId(tenantId);
        addendum.setEncounterId(encounter.getId());
        addendum.setAuthorId(authorUserId);
        addendum.setText(text);
        return encounterAddendumRepository.save(addendum);
    }

    private void requireUnlocked(Encounter encounter) {
        if (encounter.getSignedAt() != null) {
            throw new EncounterLockedException(
                    "This encounter was signed on " + encounter.getSignedAt() + " and is locked - add an addendum instead");
        }
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
