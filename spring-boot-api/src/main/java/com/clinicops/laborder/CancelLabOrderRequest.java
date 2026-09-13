package com.clinicops.laborder;

/** reason is optional - a cancellation is valid with no reason given, mirrors CancelAppointmentRequest. */
public record CancelLabOrderRequest(String reason) {
}
