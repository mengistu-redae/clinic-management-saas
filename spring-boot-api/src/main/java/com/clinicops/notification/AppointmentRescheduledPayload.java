package com.clinicops.notification;

import java.math.BigDecimal;
import java.time.Instant;

/** Written by RescheduleService, rendered by SmtpEmailSender. {@code feeAmount} may be zero (no fee configured/applicable). */
public record AppointmentRescheduledPayload(String appointmentRef, Instant newStartTime, BigDecimal feeAmount) {
}
