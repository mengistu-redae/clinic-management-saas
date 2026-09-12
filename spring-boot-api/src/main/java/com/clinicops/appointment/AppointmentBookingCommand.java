package com.clinicops.appointment;

import java.util.UUID;

/**
 * Internal parameter object for AppointmentService.createAppointment - the
 * channel-specific request DTOs (CreateAppointmentRequest/
 * CreateGuestAppointmentRequest) and JWT-derived identity all collapse into
 * this one shape before reaching the shared booking logic, the same way
 * the reference project's BookingService.createBooking takes its channel/
 * customerUserId/agentUserId/etc. as separate resolved parameters rather
 * than a raw request + Jwt.
 *
 * @param callerTenantId     the front_desk caller's own clinic - required and tenant-checked for that channel only
 * @param patientId          the resolved Patient this appointment is for - null only for "guest"
 * @param customerUserId     set for patient_portal only (the resolved AppUser id, for ownership-scoped "my appointments")
 * @param recipientEmail     transient - who to notify, if anyone; never persisted on the appointment itself
 * @param seriesId           set only when this is one occurrence of a recurring series
 */
public record AppointmentBookingCommand(
        UUID slotId,
        UUID providerId,
        UUID appointmentTypeId,
        String channel,
        UUID callerTenantId,
        UUID patientId,
        UUID customerUserId,
        String contactName,
        String contactPhone,
        String recipientEmail,
        String idempotencyKey,
        UUID seriesId,
        Integer seriesOccurrenceIndex
) {
    public static AppointmentBookingCommand singleBooking(
            UUID slotId, UUID providerId, UUID appointmentTypeId, String channel, UUID callerTenantId,
            UUID patientId, UUID customerUserId, String contactName, String contactPhone,
            String recipientEmail, String idempotencyKey) {
        return new AppointmentBookingCommand(
                slotId, providerId, appointmentTypeId, channel, callerTenantId, patientId, customerUserId,
                contactName, contactPhone, recipientEmail, idempotencyKey, null, null);
    }
}
