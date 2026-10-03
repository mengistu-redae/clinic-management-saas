package com.clinicops.notification;

/**
 * Written by AppointmentReminderScheduler (patient engagement, SMS
 * reminders, sketched 2026-10-04) - never actually rendered by any sender
 * today (see Notification.status on these rows - always written as
 * "skipped_no_gateway", never "pending", so NotificationWorker's own
 * poll query never picks them up). Shaped the same deliberately-narrow
 * way CriticalLabValueAlertPayload/LabResultReadyPayload already are, so
 * a real SmsSender could be dropped in later with no payload rework.
 */
public record AppointmentReminderPayload(String appointmentRef, String startTimeIso) {
}
