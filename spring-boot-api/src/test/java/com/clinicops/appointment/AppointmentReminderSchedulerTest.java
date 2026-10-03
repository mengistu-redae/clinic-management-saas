package com.clinicops.appointment;

import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentReminderSchedulerTest {

    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final AppointmentReminderScheduler scheduler =
            new AppointmentReminderScheduler(appointmentRepository, notificationRepository, new ObjectMapper());

    private AppointmentReminderCandidate candidate(UUID id, String contactPhone, String patientPhone) {
        AppointmentReminderCandidate c = mock(AppointmentReminderCandidate.class);
        when(c.getId()).thenReturn(id);
        when(c.getTenantId()).thenReturn(UUID.randomUUID());
        when(c.getAppointmentRef()).thenReturn("REF123");
        when(c.getContactPhone()).thenReturn(contactPhone);
        when(c.getPatientPhone()).thenReturn(patientPhone);
        when(c.getStartTime()).thenReturn(Instant.now().plusSeconds(86_000));
        return c;
    }

    private void stubFindById(UUID id) {
        Appointment appointment = new Appointment();
        appointment.setId(id);
        when(appointmentRepository.findById(id)).thenReturn(Optional.of(appointment));
    }

    @Test
    void writesAnSmsOutboxRowAlreadySkippedNotPendingForAPatientPortalBooking() {
        UUID id = UUID.randomUUID();
        stubFindById(id);
        AppointmentReminderCandidate candidate = candidate(id, null, "+15551230000");
        when(appointmentRepository.findReminderCandidates(any(), any())).thenReturn(List.of(candidate));

        scheduler.sendUpcomingReminders();

        var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getChannel()).isEqualTo("sms");
        assertThat(saved.getRecipient()).isEqualTo("+15551230000");
        assertThat(saved.getStatus()).isEqualTo("skipped_no_gateway");
        assertThat(saved.getType()).isEqualTo("appointment_reminder");

        verify(appointmentRepository).save(org.mockito.ArgumentMatchers.argThat(a -> a.getReminderSentAt() != null));
    }

    @Test
    void fallsBackToContactPhoneForAGuestBookingWithNoPatientRecord() {
        UUID id = UUID.randomUUID();
        stubFindById(id);
        AppointmentReminderCandidate candidate = candidate(id, "+15559990000", null);
        when(appointmentRepository.findReminderCandidates(any(), any())).thenReturn(List.of(candidate));

        scheduler.sendUpcomingReminders();

        var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipient()).isEqualTo("+15559990000");
    }

    @Test
    void skipsSilentlyButStillMarksRemindedWhenNeitherPhoneIsOnFile() {
        UUID id = UUID.randomUUID();
        stubFindById(id);
        AppointmentReminderCandidate candidate = candidate(id, null, null);
        when(appointmentRepository.findReminderCandidates(any(), any())).thenReturn(List.of(candidate));

        scheduler.sendUpcomingReminders();

        verify(notificationRepository, never()).save(any());
        verify(appointmentRepository).save(org.mockito.ArgumentMatchers.argThat(a -> a.getReminderSentAt() != null));
    }

    @Test
    void processesEveryCandidateInOneRun() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        stubFindById(id1);
        stubFindById(id2);
        AppointmentReminderCandidate candidate1 = candidate(id1, "+15550000001", null);
        AppointmentReminderCandidate candidate2 = candidate(id2, "+15550000002", null);
        when(appointmentRepository.findReminderCandidates(any(), any())).thenReturn(List.of(candidate1, candidate2));

        scheduler.sendUpcomingReminders();

        verify(notificationRepository, org.mockito.Mockito.times(2)).save(any());
    }
}
