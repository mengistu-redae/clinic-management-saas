package com.clinicops.encounter;

import java.util.List;

/**
 * The GET read-shape: bundles the encounter with its prescription list and
 * (phase 12) its addenda, same "wrapper record bundles the aggregate root
 * plus its child lists" convention already used by AppointmentSeriesResult -
 * neither Prescription nor EncounterAddendum has a JPA relation back to
 * Encounter (plain FK columns, like everywhere else in this codebase), so
 * all three are fetched separately and composed here. {@code addenda} is
 * ordered oldest-first, matching how a running clinical note reads.
 */
public record EncounterWithPrescriptions(Encounter encounter, List<Prescription> prescriptions, List<EncounterAddendum> addenda) {
}
