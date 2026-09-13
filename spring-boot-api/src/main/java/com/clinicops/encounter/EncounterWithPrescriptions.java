package com.clinicops.encounter;

import java.util.List;

/**
 * The GET read-shape: bundles the encounter with its prescription list,
 * same "wrapper record bundles the aggregate root plus its child list"
 * convention already used by AppointmentSeriesResult - Prescription has no
 * JPA relation back to Encounter (plain FK column, like everywhere else in
 * this codebase), so the two are fetched separately and composed here.
 */
public record EncounterWithPrescriptions(Encounter encounter, List<Prescription> prescriptions) {
}
