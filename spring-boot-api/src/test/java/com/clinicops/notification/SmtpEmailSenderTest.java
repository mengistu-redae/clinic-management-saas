package com.clinicops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pure Mockito unit test - no Spring context, no real SMTP connection
 * (JavaMailSender is mocked) - same "actually runs locally" style as
 * NotificationWorkerTest/ClinicProvisioningServiceTest.
 */
class SmtpEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SmtpEmailSender sender = new SmtpEmailSender(mailSender, objectMapper, "no-reply@clinicops.local");

    private Notification notification(String type, Object payload) throws Exception {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setTenantId(UUID.randomUUID());
        notification.setRecipient("patient@example.com");
        notification.setType(type);
        notification.setPayload(objectMapper.writeValueAsString(payload));
        return notification;
    }

    private SimpleMailMessage sentMessage() {
        var captor = forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void rendersAnAppointmentConfirmedEmail() throws Exception {
        Instant startTime = Instant.parse("2026-10-01T14:30:00Z");
        Notification notification = notification("appointment_confirmed",
                new AppointmentConfirmedPayload("ABC123", "Demo Clinic", startTime));

        sender.send(notification);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getFrom()).isEqualTo("no-reply@clinicops.local");
        assertThat(message.getTo()).containsExactly("patient@example.com");
        assertThat(message.getSubject()).contains("confirmed").contains("ABC123");
        assertThat(message.getText()).contains("Demo Clinic").contains("ABC123");
    }

    @Test
    void rendersAnAppointmentCancelledEmailWithAFeeAndReason() throws Exception {
        Notification notification = notification("appointment_cancelled",
                new AppointmentCancelledPayload("REF1", new BigDecimal("15.00"), "patient request"));

        sender.send(notification);

        String body = sentMessage().getText();
        assertThat(body).contains("cancelled").contains("patient request").contains("15.00");
    }

    @Test
    void rendersAnAppointmentCancelledEmailWithNoFeeOrReasonOmittingBoth() throws Exception {
        Notification notification = notification("appointment_cancelled",
                new AppointmentCancelledPayload("REF2", BigDecimal.ZERO, null));

        sender.send(notification);

        String body = sentMessage().getText();
        assertThat(body).doesNotContain("Reason:").doesNotContain("fee");
    }

    @Test
    void rendersAnAppointmentRescheduledEmail() throws Exception {
        Instant newStartTime = Instant.parse("2026-10-05T09:00:00Z");
        Notification notification = notification("appointment_rescheduled",
                new AppointmentRescheduledPayload("REF3", newStartTime, new BigDecimal("5.00")));

        sender.send(notification);

        String body = sentMessage().getText();
        assertThat(body).contains("REF3").contains("5.00");
    }

    @Test
    void rendersALabResultReadyEmail() throws Exception {
        Notification notification = notification("lab_result_ready", new LabResultReadyPayload("LAB99"));

        sender.send(notification);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).contains("LAB99");
        assertThat(message.getText()).contains("LAB99");
    }

    @Test
    void anUnknownTypeStillSendsAGenericFallbackRatherThanThrowing() throws Exception {
        Notification notification = new Notification();
        notification.setRecipient("patient@example.com");
        notification.setType("something_new");
        notification.setPayload("{}");

        sender.send(notification);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isNotBlank();
        assertThat(message.getText()).isNotBlank();
    }
}
