package com.clinicops.notification;

/** Written by LabOrderStatusService.review, rendered by SmtpEmailSender. Deliberately narrow - no result values here, matching LabOrderTrackingView's own "never leak clinical content into a notification" convention. */
public record LabResultReadyPayload(String orderRef) {
}
