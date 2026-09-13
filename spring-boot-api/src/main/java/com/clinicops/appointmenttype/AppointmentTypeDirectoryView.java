package com.clinicops.appointmenttype;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The public, pre-auth view of a clinic's appointment types - id/name/
 * duration/price only, same "deliberately narrow projection" precedent as
 * ClinicDirectoryView. Lets a patient/guest discover what to book before
 * calling POST /api/appointments, which already requires an
 * appointmentTypeId with no other way to look one up.
 */
public record AppointmentTypeDirectoryView(UUID id, String name, int durationMinutes, BigDecimal priceAmount) {
    static AppointmentTypeDirectoryView from(AppointmentType type) {
        return new AppointmentTypeDirectoryView(type.getId(), type.getName(), type.getDurationMinutes(), type.getPriceAmount());
    }
}
