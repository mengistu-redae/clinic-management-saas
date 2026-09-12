package com.clinicops.appointment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Shared by both authenticated channels (patient_portal/front_desk) - see
 * AppointmentController.createAppointment, which derives the channel from
 * the caller's JWT role, never from a client-supplied field.
 *
 * `patientId` is required for front_desk (an existing Patient, tenant
 * -checked) and ignored for patient_portal, whose patient is instead
 * resolved via PatientProvisioningService - a patient never gets to name
 * which patient record they're booking as.
 */
public record CreateAppointmentRequest(
        @NotNull UUID slotId,
        @NotNull UUID providerId,
        @NotNull UUID appointmentTypeId,
        UUID patientId,
        @NotNull String idempotencyKey
) {
}
