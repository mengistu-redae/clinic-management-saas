package com.clinicops.notification;

import java.math.BigDecimal;

/** Written by CancellationService, rendered by SmtpEmailSender. {@code feeAmount} may be zero (no fee configured/applicable). */
public record AppointmentCancelledPayload(String appointmentRef, BigDecimal feeAmount, String reason) {
}
