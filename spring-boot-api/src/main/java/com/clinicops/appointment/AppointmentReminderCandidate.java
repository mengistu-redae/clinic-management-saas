package com.clinicops.appointment;

import java.time.Instant;
import java.util.UUID;

/**
 * Backs {@link AppointmentRepository#findReminderCandidates} -
 * {@code patientPhone} is resolved via a LEFT JOIN to patients so
 * {@link AppointmentReminderScheduler} never needs a second lookup;
 * {@code contactPhone} is the guest-channel fallback already on the
 * appointment row itself (same "embed what the caller needs" convention
 * {@code AppointmentWorklistView} already established).
 */
public interface AppointmentReminderCandidate {
    UUID getId();

    UUID getTenantId();

    String getAppointmentRef();

    String getContactPhone();

    String getPatientPhone();

    Instant getStartTime();
}
