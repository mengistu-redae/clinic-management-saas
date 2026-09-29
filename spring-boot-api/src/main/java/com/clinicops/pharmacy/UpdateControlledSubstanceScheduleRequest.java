package com.clinicops.pharmacy;

/** {@code schedule} null clears it - correcting a data-entry mistake. */
public record UpdateControlledSubstanceScheduleRequest(String schedule) {
}
