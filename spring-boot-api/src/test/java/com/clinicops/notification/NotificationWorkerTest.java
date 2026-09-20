package com.clinicops.notification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test - no Spring context, no Testcontainers - same style
 * as ClinicProvisioningServiceTest/FeeCalculatorTest, chosen so this suite
 * actually runs on a dev machine the Testcontainers suite can't (see
 * CLAUDE.md's known gaps).
 */
class NotificationWorkerTest {

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationSender notificationSender = mock(NotificationSender.class);
    private final NotificationWorker worker = new NotificationWorker(notificationRepository, notificationSender);

    private Notification pending(int attempts) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setTenantId(UUID.randomUUID());
        notification.setRecipient("patient@example.com");
        notification.setType("appointment_confirmed");
        notification.setAttempts(attempts);
        return notification;
    }

    @Test
    void aSuccessfulSendMarksTheNotificationSent() throws Exception {
        Notification notification = pending(0);
        when(notificationRepository.findTop50ByStatusOrderByCreatedAtAsc("pending")).thenReturn(List.of(notification));
        doNothing().when(notificationSender).send(notification);

        worker.dispatchPending();

        assertThat(notification.getStatus()).isEqualTo("sent");
        assertThat(notification.getSentAt()).isNotNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    void aFailureBelowMaxAttemptsIncrementsAttemptsAndStaysPending() throws Exception {
        Notification notification = pending(1);
        when(notificationRepository.findTop50ByStatusOrderByCreatedAtAsc("pending")).thenReturn(List.of(notification));
        doThrow(new RuntimeException("provider is down")).when(notificationSender).send(any());

        worker.dispatchPending();

        assertThat(notification.getAttempts()).isEqualTo(2);
        assertThat(notification.getStatus()).isEqualTo("pending");
        verify(notificationRepository).save(notification);
    }

    @Test
    void aFailureAtMaxAttemptsMarksTheNotificationFailed() throws Exception {
        Notification notification = pending(4);
        when(notificationRepository.findTop50ByStatusOrderByCreatedAtAsc("pending")).thenReturn(List.of(notification));
        doThrow(new RuntimeException("provider is down")).when(notificationSender).send(any());

        worker.dispatchPending();

        assertThat(notification.getAttempts()).isEqualTo(5);
        assertThat(notification.getStatus()).isEqualTo("failed");
    }
}
