package com.clinicops.notification;

/**
 * Written by AnalyteResultService.enterResults when a result lands outside
 * its own analyte's critical range, rendered by SmtpEmailSender. Deliberately
 * narrow - no analyte name or the actual value, matching LabResultReadyPayload's
 * own "never leak clinical content into a notification" convention; the
 * recipient logs into the app to see what's actually critical.
 */
public record CriticalLabValueAlertPayload(String orderRef, String testName) {
}
