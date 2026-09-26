package com.clinicops.appointment;

import java.time.Instant;
import java.util.UUID;

/**
 * The staff-worklist counterpart of {@link AppointmentWithSlotView} - same
 * "join slots for a real startTime, since Appointment itself carries none"
 * reasoning, but for a *staff* reader rather than the patient reading their
 * own appointment: also carries {@code contactName}/{@code contactPhone}
 * (a guest booking's own contact info, already on the plain entity) so a
 * front-desk/provider row can show a real name without a second lookup for
 * the guest case - a `patientId` case still needs the caller's own
 * `GET /api/patients` id->name map, matching the pattern
 * `front-desk/Appointments.jsx` already established, not a server-side
 * join to `patients` (this app resolves id->name client-side everywhere
 * else - providers in the analytics utilization chart, patients here -
 * rather than joining a second table per consumer).
 *
 * Backs `GET /api/appointments/worklist` (front-desk/clinic-admin
 * dashboard) and `GET /api/my-schedule` (provider dashboard) - two
 * different call sites, identical shape need, one projection.
 */
public interface AppointmentWorklistView {
    UUID getId();

    UUID getTenantId();

    UUID getPatientId();

    UUID getProviderId();

    UUID getAppointmentTypeId();

    String getChannel();

    String getStatus();

    String getAppointmentRef();

    String getClinicRef();

    String getContactName();

    String getContactPhone();

    Instant getStartTime();

    Instant getEndTime();

    Instant getBookedAt();

    Instant getCancelledAt();

    String getCancellationReason();
}
