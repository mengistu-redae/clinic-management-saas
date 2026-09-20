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
 * attempt count) via whatever NotificationSender bean is wired - only
 * LoggingEmailSender (a stub) exists so far, see its own javadoc; `payload`
 * stays a placeholder "{}" until an actual email template is built. Stored
 * as jsonb via Hibernate's native JSON type mapping
 * (@JdbcTypeCode(SqlTypes.JSON)) rather than a plain VARCHAR, so the column
 * stays real jsonb for whenever payload content is added.
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
