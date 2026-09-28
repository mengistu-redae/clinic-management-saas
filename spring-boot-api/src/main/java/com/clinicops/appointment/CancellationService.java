package com.clinicops.appointment;

import com.clinicops.accounting.JournalService;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.feepolicy.FeeCalculator;
import com.clinicops.notification.AppointmentCancelledPayload;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationPayloadWriter;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.payment.Payment;
import com.clinicops.payment.PaymentRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Two public entry points - {@link #cancel} (staff, tenant-scoped) and
 * {@link #cancelAsCustomer} (patient, ownership-scoped) - kept separate
 * because their lookups are scoped completely differently, mirroring the
 * reference project's CancellationService exactly. Each is its own
 * {@code @Transactional} boundary; both delegate to the same private
 * {@code applyCancellation} - safe here (unlike calling a
 * {@code @Transactional} method on {@code this} from a *non*-transactional
 * method) because it always runs inside whichever public method's already
 * -active transaction called it.
 */
@Service
public class CancellationService {

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final PatientRepository patientRepository;
    private final FeeCalculator feeCalculator;
    private final AppointmentCancellationRepository appointmentCancellationRepository;
    private final NotificationRepository notificationRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;
    private final JournalService journalService;

    public CancellationService(
            AppointmentRepository appointmentRepository,
            SlotRepository slotRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            PatientRepository patientRepository,
            FeeCalculator feeCalculator,
            AppointmentCancellationRepository appointmentCancellationRepository,
            NotificationRepository notificationRepository,
            PaymentRepository paymentRepository,
            ObjectMapper objectMapper,
            JournalService journalService) {
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.patientRepository = patientRepository;
        this.feeCalculator = feeCalculator;
        this.appointmentCancellationRepository = appointmentCancellationRepository;
        this.notificationRepository = notificationRepository;
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
        this.journalService = journalService;
    }

    @Transactional
    public Appointment cancel(UUID appointmentId, UUID tenantId, UUID cancelledByUserId, String reason) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        return applyCancellation(appointment, cancelledByUserId, cancelledByUserId, reason);
    }

    @Transactional
    public Appointment cancelAsCustomer(UUID appointmentId, UUID customerUserId, String reason) {
        Appointment appointment = appointmentRepository.findByIdAndCustomerUserId(appointmentId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        return applyCancellation(appointment, null, customerUserId, reason);
    }

    /**
     * "Cancel this and the rest of the series" - staff-only, triggered from
     * one specific occurrence. Scoped to every still-{@code booked}
     * occurrence sharing that appointment's own seriesId (the triggering
     * occurrence included, if it's still booked itself) rather than only
     * ones chronologically after it - a plain {@code status = 'booked'}
     * filter already means "not yet happened and not already cancelled,"
     * so there's no need for a separate time comparison. Each occurrence
     * goes through the exact same {@link #applyCancellation} path a single
     * cancel uses - same fee-tier calculation, same fee_auto_charged
     * Payment/journal posting, same per-occurrence audit row and
     * notification - deliberately not a bulk shortcut that skips any of
     * that.
     */
    @Transactional
    public SeriesCancellationResult cancelSeries(UUID appointmentId, UUID tenantId, UUID cancelledByUserId, String reason) {
        Appointment triggering = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        if (triggering.getSeriesId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Appointment " + appointmentId + " is not part of a recurring series");
        }

        List<Appointment> occurrences = appointmentRepository.findAllByTenantIdAndSeriesId(tenantId, triggering.getSeriesId());
        List<Appointment> cancelled = new ArrayList<>();
        for (Appointment occurrence : occurrences) {
            if (!"booked".equals(occurrence.getStatus())) {
                continue; // already cancelled, or otherwise past the point where a bulk cancel still applies
            }
            cancelled.add(applyCancellation(occurrence, cancelledByUserId, cancelledByUserId, reason));
        }
        return new SeriesCancellationResult(cancelled);
    }

    /**
     * cancelledByUserId and payerUserId are deliberately separate params,
     * not one reused for both: cancelledByUserId is staff-attribution only
     * (stays null for a patient's own self-cancel, an existing signal this
     * doesn't change) while payerUserId is whoever actually triggered the
     * fee - the same staff id for the staff path, but the patient's own id
     * for self-cancel, since a real person did initiate the charge there
     * too.
     */
    private Appointment applyCancellation(Appointment appointment, UUID cancelledByUserId, UUID payerUserId, String reason) {
        if ("cancelled".equals(appointment.getStatus())) {
            throw new AppointmentAlreadyCancelledException(
                    "Appointment already cancelled: " + appointment.getId());
        }

        Slot slot = slotRepository.findById(appointment.getSlotId())
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + appointment.getSlotId()));
        AppointmentType type = appointmentTypeRepository.findById(appointment.getAppointmentTypeId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Appointment type not found: " + appointment.getAppointmentTypeId()));

        BigDecimal feeAmount = feeCalculator.calculate(
                appointment.getTenantId(), appointment.getProviderId(), type.getPriceAmount(), slot.getStartTime());

        appointment.setStatus("cancelled");
        appointment.setCancelledAt(java.time.Instant.now());
        appointment.setCancellationReason(reason);
        appointmentRepository.save(appointment);

        slot.setStatus("open");
        slotRepository.save(slot);

        AppointmentCancellation cancellation = new AppointmentCancellation();
        cancellation.setTenantId(appointment.getTenantId());
        cancellation.setAppointmentId(appointment.getId());
        cancellation.setCancelledBy(cancelledByUserId);
        cancellation.setReason(reason);
        cancellation.setFeeAmount(feeAmount);
        appointmentCancellationRepository.save(cancellation);

        if (feeAmount.signum() > 0) {
            Payment payment = new Payment();
            payment.setTenantId(appointment.getTenantId());
            payment.setAppointmentId(appointment.getId());
            payment.setAmount(feeAmount);
            payment.setMethod(Payment.FEE_AUTO_CHARGE_METHOD);
            payment.setRecordedBy(payerUserId);
            payment = paymentRepository.save(payment);
            journalService.postForPayment(payment);
        }

        // Outbox write - skipped when there's no recipient on file (a
        // front-desk cancel for a walk-in with no email captured, or a
        // guest) rather than failing the cancellation over a missing email.
        String recipientEmail = appointment.getPatientId() != null
                ? patientRepository.findById(appointment.getPatientId()).map(Patient::getEmail).orElse(null)
                : null;
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            Notification notification = new Notification();
            notification.setTenantId(appointment.getTenantId());
            notification.setRecipient(recipientEmail);
            notification.setType("appointment_cancelled");
            notification.setPayload(NotificationPayloadWriter.toJson(objectMapper,
                    new AppointmentCancelledPayload(appointment.getAppointmentRef(), feeAmount, reason)));
            notificationRepository.save(notification);
        }

        return appointment;
    }
}
