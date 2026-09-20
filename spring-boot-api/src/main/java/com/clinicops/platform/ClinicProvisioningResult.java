package com.clinicops.platform;

import com.clinicops.clinic.Clinic;

/**
 * Same "bundle the just-created extras" wrapper-record shape as
 * EncounterWithPrescriptions/LabOrderWithTests/AppointmentSeriesResult.
 *
 * initialAdminTemporaryPassword is non-null only when the create request
 * included adminEmail - shown to the platform_admin exactly once, in this
 * response. Never persisted, never logged, never retrievable again: no SMTP
 * is configured for this realm in local dev (see CLAUDE.md's known gaps),
 * so a platform_admin still has to hand it to the new clinic's admin out of
 * band (phone, Slack, whatever) rather than relying on a reset-password
 * email.
 */
public record ClinicProvisioningResult(Clinic clinic, String initialAdminTemporaryPassword) {
}
