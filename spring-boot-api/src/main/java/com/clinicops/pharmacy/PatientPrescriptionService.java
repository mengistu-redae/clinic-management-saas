package com.clinicops.pharmacy;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationPayloadWriter;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.notification.RefillReadyPayload;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * One service for the whole patient-facing pharmacy domain (phase 33),
 * same "one service for the whole domain" shape {@code LabOrderService}
 * already established. {@code myPrescriptions} resolves ownership the
 * same way {@code LabOrderService.myLabOrders} does
 * (`Appointment.findAllByCustomerUserId` -> `Encounter.findByAppointmentId`),
 * minus that method's own direct-`customerUserId`-on-the-resource union
 * half - `Prescription` has no such column and never will, since a
 * patient can't create their own prescription, only a provider can.
 */
@Service
public class PatientPrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final PrescriptionRefillRequestRepository refillRequestRepository;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterRepository encounterRepository;
    private final PatientRepository patientRepository;
    private final NotificationRepository notificationRepository;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public PatientPrescriptionService(
            PrescriptionRepository prescriptionRepository,
            PrescriptionRefillRequestRepository refillRequestRepository,
            DispenseRecordRepository dispenseRecordRepository,
            AppointmentRepository appointmentRepository,
            EncounterRepository encounterRepository,
            PatientRepository patientRepository,
            NotificationRepository notificationRepository,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper) {
        this.prescriptionRepository = prescriptionRepository;
        this.refillRequestRepository = refillRequestRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.appointmentRepository = appointmentRepository;
        this.encounterRepository = encounterRepository;
        this.patientRepository = patientRepository;
        this.notificationRepository = notificationRepository;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    public List<MyPrescriptionView> myPrescriptions(Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return ownedEncounterIds(customerUserId).stream()
                .flatMap(encounterId -> prescriptionRepository.findAllByEncounterId(encounterId).stream())
                .map(this::toView)
                .toList();
    }

    @Transactional
    public PrescriptionRefillRequest createRefillRequest(UUID prescriptionId, String notes, UUID requestedBy) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + prescriptionId));
        UUID patientId = requireOwnedByPortalUser(prescription, requestedBy);

        if (!"active".equals(prescription.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only an active prescription can be refilled");
        }
        if (refillRequestRepository.existsByPrescriptionIdAndStatus(prescriptionId, "requested")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A refill request for this prescription is already pending");
        }

        PrescriptionRefillRequest refill = new PrescriptionRefillRequest();
        refill.setTenantId(prescription.getTenantId());
        refill.setPrescriptionId(prescriptionId);
        refill.setPatientId(patientId);
        refill.setRequestedBy(requestedBy);
        refill.setNotes(notes);
        return refillRequestRepository.save(refill);
    }

    public List<PrescriptionRefillRequest> myRefillRequests(Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return refillRequestRepository.findAllByRequestedBy(customerUserId);
    }

    /** Flips requested -> approved and, if the patient has an email on file, writes a refill_ready Notification row - exact copy of LabOrderStatusService.review's own pattern. */
    @Transactional
    public PrescriptionRefillRequest approve(UUID id, UUID tenantId, UUID reviewedBy) {
        PrescriptionRefillRequest refill = requireTransitionable(id, tenantId);
        refill.setStatus("approved");
        refill.setReviewedBy(reviewedBy);
        refill.setReviewedAt(Instant.now());
        PrescriptionRefillRequest saved = refillRequestRepository.save(refill);

        String recipientEmail = patientRepository.findById(refill.getPatientId()).map(Patient::getEmail).orElse(null);
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            String medicationName = prescriptionRepository.findById(refill.getPrescriptionId())
                    .map(Prescription::getMedicationName).orElse("your medication");
            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setRecipient(recipientEmail);
            notification.setType("refill_ready");
            notification.setPayload(NotificationPayloadWriter.toJson(objectMapper, new RefillReadyPayload(medicationName)));
            notificationRepository.save(notification);
        }
        return saved;
    }

    /** Deliberately sends no notification - the sketch only ever names the "ready" message for approval. */
    @Transactional
    public PrescriptionRefillRequest deny(UUID id, UUID tenantId, UUID reviewedBy, String reviewNotes) {
        PrescriptionRefillRequest refill = requireTransitionable(id, tenantId);
        refill.setStatus("denied");
        refill.setReviewedBy(reviewedBy);
        refill.setReviewedAt(Instant.now());
        refill.setReviewNotes(reviewNotes);
        return refillRequestRepository.save(refill);
    }

    private PrescriptionRefillRequest requireTransitionable(UUID id, UUID tenantId) {
        PrescriptionRefillRequest refill = refillRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Refill request not found: " + id));
        if (!"requested".equals(refill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This refill request has already been " + refill.getStatus());
        }
        return refill;
    }

    private List<UUID> ownedEncounterIds(UUID customerUserId) {
        return appointmentRepository.findAllByCustomerUserId(customerUserId).stream()
                .map(Appointment::getId)
                .map(encounterRepository::findByAppointmentId)
                .flatMap(java.util.Optional::stream)
                .map(Encounter::getId)
                .toList();
    }

    /** 404, not 403, if the prescription isn't reachable through the caller's own appointments - this app's own established "don't leak existence" convention for patient-owned resources. */
    private UUID requireOwnedByPortalUser(Prescription prescription, UUID customerUserId) {
        Encounter encounter = encounterRepository.findById(prescription.getEncounterId()).orElse(null);
        Appointment appointment = encounter != null ? appointmentRepository.findById(encounter.getAppointmentId()).orElse(null) : null;
        if (appointment == null || !customerUserId.equals(appointment.getCustomerUserId())) {
            throw new NoSuchElementException("Prescription not found: " + prescription.getId());
        }
        return appointment.getPatientId();
    }

    private MyPrescriptionView toView(Prescription p) {
        long dispensed = dispenseRecordRepository.sumQuantityByPrescriptionId(p.getId());
        return new MyPrescriptionView(p.getId(), p.getEncounterId(), p.getMedicationName(), p.getDosage(), p.getInstructions(),
                p.getRoute(), p.getFrequency(), p.getDuration(), p.getQuantityDispensed(), p.getRefillsAllowed(),
                p.getStatus(), p.getCreatedAt(), dispensed);
    }
}
