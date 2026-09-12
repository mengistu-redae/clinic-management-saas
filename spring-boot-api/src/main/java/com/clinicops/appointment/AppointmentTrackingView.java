package com.clinicops.appointment;

import java.time.Instant;

/**
 * Public track-by-ref-and-phone response - deliberately narrow (status,
 * timestamps, clinic/provider/time only - never clinical detail, ID
 * numbers, or money), mirroring the reference project's BookingTrackingView.
 * This path has no login and no tenant check beyond the phone match done in
 * AppointmentService.trackByRefAndPhone.
 */
public record AppointmentTrackingView(
        String appointmentRef,
        String status,
        String clinicName,
        String providerName,
        Instant startTime,
        Instant bookedAt
) {
}
