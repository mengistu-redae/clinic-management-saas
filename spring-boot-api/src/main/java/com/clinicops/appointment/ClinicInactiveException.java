package com.clinicops.appointment;

/**
 * Maps to HTTP 409 in AppointmentController - the slot's clinic has been
 * deactivated (Clinic.status != "active"). Checked explicitly in
 * AppointmentService rather than relying solely on TenantContextFilter's
 * own lockout, because patient_portal/guest tokens carry no org claim, so
 * that filter never runs its check for them - this is the same
 * belt-and-suspenders check the reference project's BookingService does
 * for exactly that reason.
 */
public class ClinicInactiveException extends RuntimeException {
    public ClinicInactiveException(String message) {
        super(message);
    }
}
