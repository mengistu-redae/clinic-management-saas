package com.clinicops.notification;

/**
 * Written by ImagingOrderStatusService.review when a report is signed off
 * with criticalFinding=true. Deliberately narrow - no actual finding text -
 * matching CriticalLabValueAlertPayload's own "never leak clinical content
 * into a notification" convention; the recipient logs into the app to see
 * what's actually critical.
 */
public record CriticalImagingFindingAlertPayload(String orderRef, String studyType) {
}
