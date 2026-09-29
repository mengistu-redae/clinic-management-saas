package com.clinicops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Real SMTP delivery (phase 17) - replaces {@code LoggingEmailSender} as
 * {@link NotificationSender}'s only bean. Points at a local, open-source
 * SMTP catcher (Mailpit, docker-compose) rather than a real vendor - no
 * SendGrid/Twilio account is available, and the user asked for a
 * locally-installed open-source mail service instead when this was
 * scoped. This is genuinely real SMTP delivery, not a stub: Mailpit just
 * happens to be the real inbox it delivers into, viewable at its own web
 * UI (:8025) instead of an external mailbox - swapping in a real vendor
 * later only means pointing {@code spring.mail.host}/{@code port} (and,
 * for one needing auth, {@code spring.mail.username}/{@code password})
 * at it; this class itself doesn't change.
 *
 * SMS is deliberately out of scope this phase (the user's own call when
 * this was picked up) - {@link Notification#getChannel()} stays
 * {@code "email"} everywhere, so no channel-routing dispatch was added to
 * {@link NotificationWorker}; introduce one if/when a second channel
 * actually exists; today it would just be forwarded dead code.
 */
@Component
public class SmtpEmailSender implements NotificationSender {

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneOffset.UTC);

    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper;
    private final String fromAddress;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            ObjectMapper objectMapper,
            @Value("${clinicops.notification.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(Notification notification) throws Exception {
        String subject;
        String body;
        switch (notification.getType()) {
            case "appointment_confirmed" -> {
                var payload = objectMapper.readValue(notification.getPayload(), AppointmentConfirmedPayload.class);
                subject = "Your appointment is confirmed - " + payload.appointmentRef();
                body = "Your appointment at " + payload.clinicName() + " is confirmed for "
                        + DISPLAY_TIME.format(payload.startTime()) + " UTC.\n\n"
                        + "Reference: " + payload.appointmentRef();
            }
            case "appointment_cancelled" -> {
                var payload = objectMapper.readValue(notification.getPayload(), AppointmentCancelledPayload.class);
                subject = "Your appointment was cancelled - " + payload.appointmentRef();
                body = "Your appointment (reference " + payload.appointmentRef() + ") has been cancelled."
                        + (payload.reason() != null && !payload.reason().isBlank() ? "\n\nReason: " + payload.reason() : "")
                        + (hasFee(payload.feeAmount()) ? "\n\nA cancellation fee of " + payload.feeAmount() + " applies." : "");
            }
            case "appointment_rescheduled" -> {
                var payload = objectMapper.readValue(notification.getPayload(), AppointmentRescheduledPayload.class);
                subject = "Your appointment was rescheduled - " + payload.appointmentRef();
                body = "Your appointment (reference " + payload.appointmentRef() + ") has been moved to "
                        + DISPLAY_TIME.format(payload.newStartTime()) + " UTC."
                        + (hasFee(payload.feeAmount()) ? "\n\nA reschedule fee of " + payload.feeAmount() + " applies." : "");
            }
            case "lab_result_ready" -> {
                var payload = objectMapper.readValue(notification.getPayload(), LabResultReadyPayload.class);
                subject = "Your lab results are ready - " + payload.orderRef();
                body = "Your lab results for order " + payload.orderRef() + " have been reviewed and are ready. "
                        + "Please contact your clinic or check your patient portal for details.";
            }
            case "refill_ready" -> {
                var payload = objectMapper.readValue(notification.getPayload(), RefillReadyPayload.class);
                subject = "Your prescription refill is ready - " + payload.medicationName();
                body = "Your refill request for " + payload.medicationName() + " has been approved and is ready. "
                        + "Please contact your clinic or check your patient portal for details.";
            }
            default -> {
                subject = "Notification from your clinic";
                body = "You have a new notification.";
            }
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(notification.getRecipient());
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    private boolean hasFee(java.math.BigDecimal feeAmount) {
        return feeAmount != null && feeAmount.signum() > 0;
    }
}
