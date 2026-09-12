package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Implements the same flow as the reference project's
 * BookingService.createBooking: resolve the slot -> acquire a short-lived
 * Redis lock -> lock acquired (write the appointment) or already locked
 * (409). The actual DB write lives in {@link AppointmentWriter}, a separate
 * bean, so its {@code @Transactional} goes through Spring's proxy correctly.
 */
@Service
public class AppointmentService {

    private final SlotLockService slotLockService;
    private final SlotRepository slotRepository;
    private final ClinicRepository clinicRepository;
    private final ProviderRepository providerRepository;
    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentWriter appointmentWriter;

    public AppointmentService(
            SlotLockService slotLockService,
            SlotRepository slotRepository,
            ClinicRepository clinicRepository,
            ProviderRepository providerRepository,
            PatientRepository patientRepository,
            AppointmentRepository appointmentRepository,
            AppointmentWriter appointmentWriter) {
        this.slotLockService = slotLockService;
        this.slotRepository = slotRepository;
        this.clinicRepository = clinicRepository;
        this.providerRepository = providerRepository;
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentWriter = appointmentWriter;
    }

    public Appointment createAppointment(AppointmentBookingCommand command) {
        Slot slot = slotRepository.findById(command.slotId())
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + command.slotId()));

        // A front_desk caller may only book their own clinic's slots.
        // patient_portal/guest carry no tenant to compare against - that's
        // fine, this check is deliberately channel-specific.
        if ("front_desk".equals(command.channel()) && !slot.getTenantId().equals(command.callerTenantId())) {
            throw new TenantMismatchException("Caller's clinic does not match this slot's clinic");
        }

        // Booking-time-only enforcement of clinic deactivation. Checked here
        // (not just at TenantContextFilter) because patient_portal/guest
        // tokens carry no org claim, so that filter's lockout never runs for
        // them - see ClinicInactiveException's javadoc. Checked before the
        // idempotency lookup/slot lock so a doomed booking never touches Redis.
        Clinic clinic = clinicRepository.findById(slot.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + slot.getTenantId()));
        if (!"active".equals(clinic.getStatus())) {
            throw new ClinicInactiveException("This clinic is not currently accepting bookings");
        }

        // Idempotency check happens before any locking - a retried request
        // with the same key just returns the original appointment, not a
        // fight over the same slot.
        var existing = appointmentRepository.findByTenantIdAndIdempotencyKey(slot.getTenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        String lockToken = UUID.randomUUID().toString();
        boolean acquired = slotLockService.tryAcquire(command.slotId().toString(), lockToken);
        if (!acquired) {
            throw new SlotConflictException("Slot already held by another request: " + command.slotId());
        }
        try {
            // The lock only proves we currently hold the slot in Redis - the
            // DB write still re-checks status='open', so a slot booked
            // through some other path can't be double-booked either.
            return appointmentWriter.write(clinic, command);
        } finally {
            slotLockService.release(command.slotId().toString(), lockToken);
        }
    }

    /**
     * Public track-by-ref lookup - see AppointmentController.trackAppointment.
     * A phone match against either the appointment's own contact phone or
     * its linked patient's phone on file stands in for an ownership check,
     * since the caller has no session/tenant/customerUserId at all. A
     * mismatch (or unknown ref) 404s identically.
     */
    public AppointmentTrackingView trackByRefAndPhone(String appointmentRef, String phone) {
        Appointment appointment = appointmentRepository.findByAppointmentRef(appointmentRef)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentRef));

        boolean phoneMatches = phone.equals(appointment.getContactPhone())
                || (appointment.getPatientId() != null && patientRepository.findById(appointment.getPatientId())
                        .map(Patient::getPhone)
                        .map(phone::equals)
                        .orElse(false));
        if (!phoneMatches) {
            throw new NoSuchElementException("Appointment not found: " + appointmentRef);
        }

        Slot slot = slotRepository.findById(appointment.getSlotId())
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + appointment.getSlotId()));
        String clinicName = clinicRepository.findById(appointment.getTenantId())
                .map(Clinic::getName).orElse(null);
        String providerName = providerRepository.findById(appointment.getProviderId())
                .map(Provider::getFullName).orElse(null);

        return new AppointmentTrackingView(
                appointment.getAppointmentRef(),
                appointment.getStatus(),
                clinicName,
                providerName,
                slot.getStartTime(),
                appointment.getBookedAt());
    }
}
