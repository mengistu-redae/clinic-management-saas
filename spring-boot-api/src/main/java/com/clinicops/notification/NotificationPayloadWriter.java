package com.clinicops.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wraps ObjectMapper's checked JsonProcessingException as unchecked -
 * every payload record in this package is a plain bundle of primitives/
 * Instant/BigDecimal, which can't realistically fail to serialize, so
 * forcing every call site to declare a checked exception for this would
 * be pure noise.
 */
public final class NotificationPayloadWriter {

    private NotificationPayloadWriter() {
    }

    public static String toJson(ObjectMapper objectMapper, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload: " + payload, e);
        }
    }
}
