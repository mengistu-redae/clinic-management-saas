package com.clinicops.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder NotificationSender: logs instead of calling a real provider -
 * same stub the reference bus-ticketing-saas project's own
 * LoggingEmailSender uses. Replace with a Spring Mail (or provider SDK)
 * implementation when real email delivery is wired up; the rest of the
 * outbox flow (writing, retrying, marking sent/failed) doesn't need to
 * change.
 */
@Component
public class LoggingEmailSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(Notification notification) {
        log.info(
                "[STUB EMAIL] to={} type={} tenant={} payload={}",
                notification.getRecipient(),
                notification.getType(),
                notification.getTenantId(),
                notification.getPayload());
    }
}
