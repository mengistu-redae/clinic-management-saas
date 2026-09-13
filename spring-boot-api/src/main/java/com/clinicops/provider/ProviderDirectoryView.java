package com.clinicops.provider;

import java.util.UUID;

/**
 * Public provider-picker shape - a patient/guest needs some way to
 * discover which providers exist at a clinic before booking, since
 * GET /api/clinics/{id}/availability already requires a providerId with no
 * other way to look one up. Deliberately narrow (no specialty/room/status/
 * appUserId) - same "narrow public directory view" precedent as
 * ClinicDirectoryView/AppointmentTypeDirectoryView.
 */
public record ProviderDirectoryView(UUID id, String fullName) {

    static ProviderDirectoryView from(Provider provider) {
        return new ProviderDirectoryView(provider.getId(), provider.getFullName());
    }
}
