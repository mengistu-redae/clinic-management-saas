package com.clinicops.laborder;

import com.clinicops.notification.LabResultReadyPayload;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationPayloadWriter;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * ordered -> specimen_collected -> in_transit -> resulted -> reviewed.
 * Mirrors CheckInService's conventions: re-calling a transition already
 * reached is idempotent (no re-validation, no re-write of already-recorded
 * data); calling one out of order throws InvalidLabOrderStatusException.
 * Each transition is its own explicit method (not a shared generic helper)
 * since result/review each carry extra payload/side-effects a bare status
 * flip doesn't have.
 */
@Service
public class LabOrderStatusService {

    private final LabOrderRepository labOrderRepository;
    private final LabOrderTestRepository labOrderTestRepository;
    private final PatientRepository patientRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public LabOrderStatusService(
            LabOrderRepository labOrderRepository,
            LabOrderTestRepository labOrderTestRepository,
            PatientRepository patientRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.labOrderRepository = labOrderRepository;
        this.labOrderTestRepository = labOrderTestRepository;
        this.patientRepository = patientRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * The presented-ID check is deliberately stricter than check-in's: no
     * ID presented, or nothing on file to compare against, is ALSO a
     * mismatch here (not allowed through) - see IdentityMismatchException's
     * javadoc for why this diverges from CheckInService on purpose.
     */
    @Transactional
    public LabOrderWithTests collectSpecimen(UUID id, UUID tenantId, String presentedIdNumber) {
        LabOrder order = findOrThrow(id, tenantId);
        if ("specimen_collected".equals(order.getStatus())) {
            return withTests(order);
        }
        if (!"ordered".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException(
                    "Cannot collect specimen for a lab order with status '" + order.getStatus() + "'");
        }

        String onFile = patientRepository.findById(order.getPatientId()).map(Patient::getNationalId).orElse(null);
        boolean presented = presentedIdNumber != null && !presentedIdNumber.isBlank();
        boolean onFileSet = onFile != null && !onFile.isBlank();
        if (!presented || !onFileSet || !onFile.equals(presentedIdNumber)) {
            throw new IdentityMismatchException("Presented ID does not match the ID on file, or nothing is on file to check against");
        }

        order.setStatus("specimen_collected");
        order.setSpecimenCollectedAt(Instant.now());
        labOrderRepository.save(order);
        return withTests(order);
    }

    @Transactional
    public LabOrderWithTests send(UUID id, UUID tenantId) {
        LabOrder order = findOrThrow(id, tenantId);
        if ("in_transit".equals(order.getStatus())) {
            return withTests(order);
        }
        if (!"specimen_collected".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot send a lab order with status '" + order.getStatus() + "'");
        }
        order.setStatus("in_transit");
        order.setSentAt(Instant.now());
        labOrderRepository.save(order);
        return withTests(order);
    }

    /** Re-calling after already resulted/reviewed is idempotent and does NOT re-write the recorded values. */
    @Transactional
    public LabOrderWithTests result(UUID id, UUID tenantId, UUID resultedByUserId, ResultLabOrderRequest request) {
        LabOrder order = findOrThrow(id, tenantId);
        if ("resulted".equals(order.getStatus()) || "reviewed".equals(order.getStatus())) {
            return withTests(order);
        }
        if (!"in_transit".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot result a lab order with status '" + order.getStatus() + "'");
        }

        Map<UUID, LabOrderTest> byId = new HashMap<>();
        for (LabOrderTest test : labOrderTestRepository.findAllByLabOrderId(order.getId())) {
            byId.put(test.getId(), test);
        }
        for (TestResultInput input : request.results()) {
            LabOrderTest test = byId.get(input.labOrderTestId());
            if (test == null) {
                throw new NoSuchElementException("Lab order test not found on this order: " + input.labOrderTestId());
            }
            test.setResultValue(input.value());
            test.setResultUnit(input.unit());
            test.setReferenceRange(input.referenceRange());
            test.setAbnormalFlag(input.abnormalFlag());
            labOrderTestRepository.save(test);
        }

        order.setStatus("resulted");
        order.setResultedAt(Instant.now());
        order.setResultedBy(resultedByUserId);
        labOrderRepository.save(order);
        return withTests(order);
    }

    /**
     * A workflow/audit stamp, not an access gate - resulted-but-unreviewed
     * values are already visible to any provider/clinic_admin reading the
     * order (decided in plan mode). Fires a lab_result_ready outbox
     * notification, skipped when the patient has no contact on file - same
     * pattern CancellationService already uses for its own outbox write.
     */
    @Transactional
    public LabOrderWithTests review(UUID id, UUID tenantId, UUID reviewedByUserId) {
        LabOrder order = findOrThrow(id, tenantId);
        if ("reviewed".equals(order.getStatus())) {
            return withTests(order);
        }
        if (!"resulted".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot review a lab order with status '" + order.getStatus() + "'");
        }

        order.setStatus("reviewed");
        order.setReviewedAt(Instant.now());
        order.setReviewedBy(reviewedByUserId);
        labOrderRepository.save(order);

        String recipientEmail = patientRepository.findById(order.getPatientId()).map(Patient::getEmail).orElse(null);
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setRecipient(recipientEmail);
            notification.setType("lab_result_ready");
            notification.setPayload(NotificationPayloadWriter.toJson(objectMapper, new LabResultReadyPayload(order.getOrderRef())));
            notificationRepository.save(notification);
        }

        return withTests(order);
    }

    private LabOrder findOrThrow(UUID id, UUID tenantId) {
        return labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));
    }

    private LabOrderWithTests withTests(LabOrder order) {
        List<LabOrderTest> tests = labOrderTestRepository.findAllByLabOrderId(order.getId());
        return new LabOrderWithTests(order, tests);
    }
}
