package com.clinicops.appointment;

import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.payment.Payment;
import com.clinicops.payment.PaymentRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Same two-public-methods-one-private-helper split as
 * {@link CancellationService}, mirroring the reference project's
 * BookingRescheduleService. Moves only the slot (and, optionally, the
 * provider within the same clinic) - patient/contact fields are never
 * touched ("immutability principle").
 *
 * Unlike the reference project (which has to insert a new booking_seats row
 * and delete the old one, in that order, because booking_infants has a
 * composite FK into booking_seats), this clinic's Appointment row has no
 * child table keyed by slot_id - so rescheduling just reassigns
 * appointment.slotId/providerId in place. Revisit this simplification if a
 * future migration ever adds a table with an FK on slot_id.
 */
@Service
public class RescheduleService {

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final SlotLockService slotLockService;
    private final AppointmentReschedulesRepository appointmentReschedulesRepository;
    private final PatientRepository patientRepository;
    private final NotificationRepository notificationRepository;
    private final ClinicSettingsService clinicSettingsService;
    private final PaymentRepository paymentRepository;

    public RescheduleService(
            AppointmentRepository appointmentRepository,
            SlotRepository slotRepository,
            SlotLockService slotLockService,
            AppointmentReschedulesRepository appointmentReschedulesRepository,
            PatientRepository patientRepository,
            NotificationRepository notificationRepository,
            ClinicSettingsService clinicSettingsService,
            PaymentRepository paymentRepository) {
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.slotLockService = slotLockService;
        this.appointmentReschedulesRepository = appointmentReschedulesRepository;
        this.patientRepository = patientRepository;
        this.notificationRepository = notificationRepository;
        this.clinicSettingsService = clinicSettingsService;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Appointment reschedule(
            UUID appointmentId, UUID tenantId, UUID newSlotId, UUID newProviderId, UUID actingUserId) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        return applyReschedule(appointment, newSlotId, newProviderId, "front_desk", actingUserId, actingUserId);
    }

    @Transactional
    public Appointment rescheduleAsCustomer(UUID appointmentId, UUID customerUserId, UUID newSlotId, UUID newProviderId) {
        Appointment appointment = appointmentRepository.findByIdAndCustomerUserId(appointmentId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        return applyReschedule(appointment, newSlotId, newProviderId, "patient_portal", null, customerUserId);
    }

    /**
     * actingUserId and payerUserId are deliberately separate params, same
     * reasoning as CancellationService's own split: actingUserId stays null
     * for a patient's own self-reschedule (AppointmentReschedule.createdBy's
     * existing "staff-attribution only" signal, unchanged), while
     * payerUserId is whoever actually triggered the fee - the patient's own
     * id on the self-service path.
     */
    private Appointment applyReschedule(
            Appointment appointment, UUID newSlotId, UUID newProviderId, String channel, UUID actingUserId, UUID payerUserId) {
        if ("cancelled".equals(appointment.getStatus())) {
            throw new AppointmentAlreadyCancelledException(
                    "Cannot reschedule a cancelled appointment: " + appointment.getId());
        }

        Slot oldSlot = slotRepository.findById(appointment.getSlotId())
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + appointment.getSlotId()));

        EffectiveClinicSettings settings = clinicSettingsService.resolve(appointment.getTenantId());
        long noticeHours = Duration.between(Instant.now(), oldSlot.getStartTime()).toHours();
        if (noticeHours < settings.rescheduleMinNoticeHours()) {
            throw new TooLateToRescheduleException(
                    "Fewer than " + settings.rescheduleMinNoticeHours() + " hours remain before this appointment - cancel it instead");
        }

        String lockToken = UUID.randomUUID().toString();
        boolean acquired = slotLockService.tryAcquire(newSlotId.toString(), lockToken);
        if (!acquired) {
            throw new SlotConflictException("Slot already held by another request: " + newSlotId);
        }
        try {
            // Re-fetch under SELECT ... FOR UPDATE and re-check status -
            // same fast-path-then-backstop shape as the original booking.
            Slot newSlot = slotRepository.findByIdAndProviderId(newSlotId, newProviderId)
                    .orElseThrow(() -> new NoSuchElementException("Slot not found for this provider: " + newSlotId));
            if (!appointment.getTenantId().equals(newSlot.getTenantId())) {
                throw new TenantMismatchException("A different clinic's slot is a new appointment, not a reschedule");
            }
            if (!appointment.getAppointmentTypeId().equals(newSlot.getAppointmentTypeId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The new slot must be for the same appointment type");
            }
            if (!"open".equals(newSlot.getStatus())) {
                throw new SlotConflictException("Slot no longer available: " + newSlotId);
            }

            UUID previousSlotId = oldSlot.getId();
            newSlot.setStatus("booked");
            slotRepository.save(newSlot);

            appointment.setSlotId(newSlot.getId());
            appointment.setProviderId(newProviderId);
            appointmentRepository.save(appointment);

            oldSlot.setStatus("open");
            slotRepository.save(oldSlot);

            BigDecimal fee = "patient_portal".equals(channel) ? settings.rescheduleFeePatientPortal() : settings.rescheduleFeeFrontDesk();

            AppointmentReschedule audit = new AppointmentReschedule();
            audit.setTenantId(appointment.getTenantId());
            audit.setAppointmentId(appointment.getId());
            audit.setPreviousSlotId(previousSlotId);
            audit.setFeeAmount(fee);
            audit.setCreatedBy(actingUserId);
            appointmentReschedulesRepository.save(audit);

            if (fee.signum() > 0) {
                Payment payment = new Payment();
                payment.setTenantId(appointment.getTenantId());
                payment.setAppointmentId(appointment.getId());
                payment.setAmount(fee);
                payment.setMethod(Payment.FEE_AUTO_CHARGE_METHOD);
                payment.setRecordedBy(payerUserId);
                paymentRepository.save(payment);
            }

            String recipientEmail = appointment.getPatientId() != null
                    ? patientRepository.findById(appointment.getPatientId()).map(Patient::getEmail).orElse(null)
                    : null;
            if (recipientEmail != null && !recipientEmail.isBlank()) {
                Notification notification = new Notification();
                notification.setTenantId(appointment.getTenantId());
                notification.setRecipient(recipientEmail);
                notification.setType("appointment_rescheduled");
                notificationRepository.save(notification);
            }

            return appointment;
        } finally {
            slotLockService.release(newSlotId.toString(), lockToken);
        }
    }
}
