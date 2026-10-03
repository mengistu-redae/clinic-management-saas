package com.clinicops.appointment;

import com.clinicops.notification.AppointmentReminderPayload;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationPayloadWriter;
import com.clinicops.notification.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Patient engagement, SMS reminders (sketched 2026-10-04, first of three
 * sequential patient-engagement phases) - writes a {@code channel="sms"}
 * row to the existing {@code notifications} outbox for every still
 * -`booked` appointment that's crossed into the reminder window, exactly
 * once per appointment ({@code reminder_sent_at} gates the re-send, same
 * "a flag gates the real invariant" reasoning {@link NoShowScheduler}
 * already uses for its own one-sided query).
 *
 * No real SMS gateway (Twilio etc.) exists in this dev environment - the
 * identical blocker phase 17 hit for email before Mailpit - so per the
 * user's own answer, this deliberately skips real delivery: the written
 * row's {@code status} is set directly to {@value #STATUS_SKIPPED}, never
 * {@code "pending"}, so {@link com.clinicops.notification.NotificationWorker}'s
 * own poll query (which only ever looks for {@code status = "pending"})
 * never picks it up and never hands it to {@link com.clinicops.notification.SmtpEmailSender}
 * (which would otherwise try to email a phone number). Swapping in a real
 * SmsSender later means writing these rows as {@code "pending"} instead
 * and registering a second {@code NotificationSender} bean keyed by
 * channel - no change needed here beyond that one status literal.
 *
 * A guest booking with no patient on file falls back to the appointment's
 * own {@code contact_phone}; an appointment with no phone at all (neither)
 * is skipped silently - the identical "skipped when there's no contact to
 * reach" precedent {@code LabOrderStatusService.review}'s own notification
 * already established.
 */
@Component
public class AppointmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderScheduler.class);
    private static final String STATUS_SKIPPED = "skipped_no_gateway";
    private static final Duration REMINDER_LEAD_TIME = Duration.ofHours(24);

    private final AppointmentRepository appointmentRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public AppointmentReminderScheduler(
            AppointmentRepository appointmentRepository, NotificationRepository notificationRepository, ObjectMapper objectMapper) {
        this.appointmentRepository = appointmentRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void sendUpcomingReminders() {
        Instant now = Instant.now();
        Instant cutoff = now.plus(REMINDER_LEAD_TIME);
        List<AppointmentReminderCandidate> candidates = appointmentRepository.findReminderCandidates(now, cutoff);
        int written = 0;
        for (AppointmentReminderCandidate candidate : candidates) {
            String phone = candidate.getPatientPhone() != null && !candidate.getPatientPhone().isBlank()
                    ? candidate.getPatientPhone()
                    : candidate.getContactPhone();
            if (phone == null || phone.isBlank()) {
                markReminded(candidate.getId());
                continue;
            }

            Notification notification = new Notification();
            notification.setTenantId(candidate.getTenantId());
            notification.setRecipient(phone);
            notification.setChannel("sms");
            notification.setType("appointment_reminder");
            notification.setPayload(NotificationPayloadWriter.toJson(
                    objectMapper, new AppointmentReminderPayload(candidate.getAppointmentRef(), candidate.getStartTime().toString())));
            notification.setStatus(STATUS_SKIPPED);
            notificationRepository.save(notification);
            markReminded(candidate.getId());
            written++;
        }
        if (written > 0) {
            log.info("Wrote {} SMS reminder outbox row(s) (no real gateway - tracked only)", written);
        }
    }

    private void markReminded(UUID appointmentId) {
        appointmentRepository.findById(appointmentId).ifPresent(a -> {
            a.setReminderSentAt(Instant.now());
            appointmentRepository.save(a);
        });
    }
}
