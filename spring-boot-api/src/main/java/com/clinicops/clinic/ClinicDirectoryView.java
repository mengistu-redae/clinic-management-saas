package com.clinicops.clinic;

import java.util.UUID;

/**
 * Public clinic-picker shape - deliberately narrow (no keycloak_org_id/
 * status) since this reaches anonymous callers. {@code timezone} (phase 18)
 * is the one exception to "narrow" - a resolved IANA zone id is
 * non-sensitive operational data, and a logged-out patient/guest needs it
 * to offer "show times in this clinic's timezone" during booking, the same
 * reasoning ClinicBrandingView's own timezone field documents.
 */
public record ClinicDirectoryView(UUID id, String name, String timezone) {

    static ClinicDirectoryView from(Clinic clinic, String timezone) {
        return new ClinicDirectoryView(clinic.getId(), clinic.getName(), timezone);
    }
}
