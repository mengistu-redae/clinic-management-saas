package com.clinicops.encounter;

/**
 * Maps to 409 in EncounterController - the encounter has been signed and
 * locked (see Encounter's own javadoc). Further changes must go through
 * POST .../encounter/addenda instead of the normal upsert/
 * replace-prescriptions endpoints. A dedicated type, not a reuse of
 * InvalidAppointmentStatusException - this is about the Encounter's own
 * lock state, not the Appointment's status (the appointment can very well
 * still be with_provider/checked_out while the encounter itself is
 * separately locked).
 */
public class EncounterLockedException extends RuntimeException {
    public EncounterLockedException(String message) {
        super(message);
    }
}
