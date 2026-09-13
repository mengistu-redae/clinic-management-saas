package com.clinicops.appointment;

/** {@code presentedIdNumber} is optional - check-in works with no ID check at all if it's omitted. */
public record CheckInRequest(String presentedIdNumber) {
}
