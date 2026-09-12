package com.clinicops.clinic;

import java.util.UUID;

/** Public clinic-picker shape - deliberately narrow (no keycloak_org_id/status) since this reaches anonymous callers. */
public record ClinicDirectoryView(UUID id, String name) {

    static ClinicDirectoryView from(Clinic clinic) {
        return new ClinicDirectoryView(clinic.getId(), clinic.getName());
    }
}
