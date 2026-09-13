package com.clinicops.appointment;

import java.time.Instant;
import java.util.UUID;

/**
 * A Spring Data native-query interface projection - the patient-facing
 * "when is my appointment" shape. Appointment itself carries no slot time
 * (it lives on Slot, joined only inside AppointmentRepository's own
 * findProviderSchedule/findWithSlot* queries, never returned as JSON) -
 * this is the first endpoint whose whole job is showing that time to the
 * caller, so it's worth the join. Backs GET /api/my-appointments and
 * GET /api/my-appointments/{id} only - the tenant-wide staff list/cancel/
 * reschedule endpoints are untouched and still return bare Appointment.
 */
public interface AppointmentWithSlotView {
    UUID getId();

    UUID getTenantId();

    UUID getPatientId();

    UUID getProviderId();

    UUID getAppointmentTypeId();

    String getChannel();

    String getStatus();

    String getAppointmentRef();

    String getClinicRef();

    Instant getStartTime();

    Instant getEndTime();

    Instant getBookedAt();

    Instant getCancelledAt();

    String getCancellationReason();
}
