package com.clinicops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesAPlainRecordToJson() {
        String json = NotificationPayloadWriter.toJson(objectMapper, new LabResultReadyPayload("LAB1"));

        assertThat(json).contains("\"orderRef\"").contains("LAB1");
    }
}
