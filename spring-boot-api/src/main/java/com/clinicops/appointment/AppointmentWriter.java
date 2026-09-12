package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * The actual DB write, in a separate {@code @Transactional} bean from
 * {@link AppointmentService} so the transaction goes through Spring's proxy
 * correctly - calling a {@code @Transactional} method on {@code this} from
 * inside the same class silently skips it. Mirrors the reference project's
 * BookingWriter almost exactly.
 */
@Service
public class AppointmentWriter {

    private final SlotRepository slotRepository;
    private final AppointmentRepository appointmentRepository;
    private final NotificationRepository notificationRepository;
    private final AppointmentRefGenerator refGenerator;

    public AppointmentWriter(
            SlotRepository slotRepository,
            AppointmentRepository appointmentRepository,
            NotificationRepository notificationRepository,
            AppointmentRefGenerator refGenerator) {
        this.slotRepository = slotRepository;
        this.appointmentRepository = appointmentRepository;
        this.notificationRepository = notificationRepository;
        this.refGenerator = refGenerator;
    }

    @Transactional
    public Appointment write(Clinic clinic, AppointmentBookingCommand command) {
        // Re-fetch under SELECT ... FOR UPDATE and re-check status - the
        // Redis lock (AppointmentService) is the fast path, this is the
        // correctness backstop. Scoped by providerId too, matching the
        // slot the caller already validated.
        Slot slot = slotRepository.findByIdAndProviderId(command.slotId(), command.providerId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Slot not found for this provider: " + command.slotId()));
        if (!"open".equals(slot.getStatus())) {
            throw new SlotConflictException("Slot no longer available: " + command.slotId());
        }

        slot.setStatus("booked");
        slotRepository.save(slot);

        Appointment appointment = new Appointment();
        appointment.setTenantId(slot.getTenantId());
        appointment.setSlotId(slot.getId());
        appointment.setPatientId(command.patientId());
        appointment.setProviderId(command.providerId());
        appointment.setAppointmentTypeId(command.appointmentTypeId());
        appointment.setChannel(command.channel());
        appointment.setCustomerUserId(command.customerUserId());
        appointment.setContactName(command.contactName());
        appointment.setContactPhone(command.contactPhone());
        // contactEmail deliberately never persisted - see CreateGuestAppointmentRequest's javadoc.
        appointment.setIdempotencyKey(command.idempotencyKey());
        appointment.setSeriesId(command.seriesId());
        appointment.setSeriesOccurrenceIndex(command.seriesOccurrenceIndex());
        appointment.setAppointmentRef(refGenerator.nextAppointmentRef());
        appointment.setClinicRef(refGenerator.nextClinicRef(slot.getTenantId(), clinic.getName()));
        appointment = appointmentRepository.save(appointment);

        // Outbox write - skipped entirely when there's no recipient (a
        // guest who left contactEmail blank, or a front_desk booking with
        // no email on file) - notifications.recipient is NOT NULL, and a
        // missing confirmation email isn't grounds to fail the booking.
        if (command.recipientEmail() != null && !command.recipientEmail().isBlank()) {
            Notification notification = new Notification();
            notification.setTenantId(slot.getTenantId());
            notification.setRecipient(command.recipientEmail());
            notification.setType("appointment_confirmed");
            notificationRepository.save(notification);
        }

        return appointment;
    }
}
