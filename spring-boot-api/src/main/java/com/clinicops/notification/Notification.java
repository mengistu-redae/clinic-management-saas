package com.clinicops.notification;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Outbox pattern: written in the same transaction as the triggering write
 * (e.g. AppointmentWriter) so a flaky email provider never fails a booking.
 * NotificationWorker drains this table (fixed-delay poll, retry with a max
 * attempt count) via whatever NotificationSender bean is wired -
 * {@link SmtpEmailSender} (phase 17) is the only bean today, real SMTP
 * delivery against a local Mailpit catcher (no SendGrid/Twilio account
 * available - see CLAUDE.md's phase-17 write-up). `payload` is one of the
 * typed *Payload records in this package (e.g. {@link AppointmentConfirmedPayload}),
 * written via {@link NotificationPayloadWriter#toJson} and keyed by
 * {@code type} for {@link SmtpEmailSender} to deserialize and render.
 * Stored as jsonb via Hibernate's native JSON type mapping
 * (@JdbcTypeCode(SqlTypes.JSON)) rather than a plain VARCHAR.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends BaseTenantEntity {

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String channel = "email";

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload = "{}";

    @Column(nullable = false)
    private String status = "pending";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "sent_at")
    private Instant sentAt;
}
