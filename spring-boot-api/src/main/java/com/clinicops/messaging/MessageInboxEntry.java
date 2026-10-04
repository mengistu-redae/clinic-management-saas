package com.clinicops.messaging;

import java.time.Instant;
import java.util.UUID;

/** Backs the staff-facing shared clinic inbox - one row per patient who has at least one message, newest-first. */
public interface MessageInboxEntry {
    UUID getPatientId();

    String getPatientName();

    Instant getLastMessageAt();

    Long getUnreadCount();
}
