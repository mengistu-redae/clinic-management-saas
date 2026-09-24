package com.clinicops.notification;

import java.time.Instant;

/** Written by AppointmentWriter, rendered by SmtpEmailSender - see Notification's own javadoc for the outbox shape. */
public record AppointmentConfirmedPayload(String appointmentRef, String clinicName, Instant startTime) {
}
