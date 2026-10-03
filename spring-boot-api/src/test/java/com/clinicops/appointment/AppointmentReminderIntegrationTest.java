package com.clinicops.appointment;

import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.notification.Notification;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real native `findReminderCandidates` query + the real
 * scheduler bean against real Postgres - the one thing a pure-unit test of
 * {@link AppointmentReminderScheduler} can't cover (the query itself).
 */
class AppointmentReminderIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AppointmentReminderScheduler scheduler;

    @Test
    void writesASkippedSmsRowForABookingWithinTheWindowAndNeverRepeatsIt() {
        Clinic clinic = createClinic("reminder-" + UUID.randomUUID(), "Reminder Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Reminder");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Patient patient = createPatient(clinic.getId(), "Remind", "Me", "+15557778888");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().plusSeconds(3600 * 20), Instant.now().plusSeconds(3600 * 21));
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        scheduler.sendUpcomingReminders();

        List<Notification> rows = notificationRepository.findAll().stream()
                .filter(n -> n.getTenantId().equals(clinic.getId()) && n.getType().equals("appointment_reminder"))
                .toList();
        assertThat(rows).hasSize(1);
        Notification row = rows.get(0);
        assertThat(row.getChannel()).isEqualTo("sms");
        assertThat(row.getRecipient()).isEqualTo("+15557778888");
        assertThat(row.getStatus()).isEqualTo("skipped_no_gateway");

        Appointment reloaded = appointmentRepository.findById(appointment.getId()).orElseThrow();
        assertThat(reloaded.getReminderSentAt()).isNotNull();

        // A second run must not write a second reminder for the same appointment.
        scheduler.sendUpcomingReminders();
        long countAfterSecondRun = notificationRepository.findAll().stream()
                .filter(n -> n.getTenantId().equals(clinic.getId()) && n.getType().equals("appointment_reminder"))
                .count();
        assertThat(countAfterSecondRun).isEqualTo(1);
    }

    @Test
    void anAppointmentOutsideTheWindowGetsNoReminderYet() {
        Clinic clinic = createClinic("reminder-far-" + UUID.randomUUID(), "Far Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Far");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Patient patient = createPatient(clinic.getId(), "Far", "Future", "+15550001111");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().plusSeconds(3600 * 48), Instant.now().plusSeconds(3600 * 49));
        createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        scheduler.sendUpcomingReminders();

        boolean any = notificationRepository.findAll().stream()
                .anyMatch(n -> n.getTenantId().equals(clinic.getId()) && n.getType().equals("appointment_reminder"));
        assertThat(any).isFalse();
    }
}
