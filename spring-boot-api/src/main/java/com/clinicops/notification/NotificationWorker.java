package com.clinicops.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Outbox dispatcher - line-for-line port of the reference bus-ticketing-saas
 * project's own NotificationWorker. AppointmentWriter/CancellationService/
 * RescheduleService/LabOrderStatusService write a pending Notification row
 * in the same DB transaction as the domain change, so the message is never
 * lost even if the sender is down - this worker just needs to retry until it
 * succeeds.
 *
 * A fixed-delay @Scheduled poller is enough at this scale; if volume grows
 * enough that polling latency matters, swap this for a message queue without
 * changing anything upstream of the outbox table.
 */
@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

    public NotificationWorker(NotificationRepository notificationRepository, NotificationSender notificationSender) {
        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
    }

    @Scheduled(fixedDelay = 10_000)
    public void dispatchPending() {
        List<Notification> pending = notificationRepository.findTop50ByStatusOrderByCreatedAtAsc("pending");
        for (Notification notification : pending) {
            try {
                notificationSender.send(notification);
                notification.setStatus("sent");
                notification.setSentAt(Instant.now());
            } catch (Exception e) {
                notification.setAttempts(notification.getAttempts() + 1);
                if (notification.getAttempts() >= MAX_ATTEMPTS) {
                    notification.setStatus("failed");
                    log.error("Notification {} failed permanently after {} attempts", notification.getId(), MAX_ATTEMPTS, e);
                } else {
                    log.warn("Notification {} failed (attempt {}), will retry", notification.getId(), notification.getAttempts(), e);
                }
            }
            notificationRepository.save(notification);
        }
    }
}
