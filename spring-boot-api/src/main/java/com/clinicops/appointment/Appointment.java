package com.clinicops.appointment;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "appointments")
@Getter
@Setter
public class Appointment extends BaseTenantEntity {

    @Column(name = "slot_id", nullable = false)
    private UUID slotId;

    /** Null only for a guest booking with no patient record at all. */
    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "appointment_type_id", nullable = false)
    private UUID appointmentTypeId;

    /** patient_portal, front_desk, or guest - decided server-side from the JWT role, never client-supplied. */
    @Column(nullable = false)
    private String channel;

    /** booked -> checked_in -> roomed -> with_provider -> checked_out, plus no_show/cancelled (phase 3). */
    @Column(nullable = false)
    private String status = "booked";

    @Column(name = "appointment_ref", nullable = false, unique = true)
    private String appointmentRef;

    @Column(name = "clinic_ref")
    private String clinicRef;

    /** Set for patient_portal bookings only - links back to the resolved AppUser via Patient.appUserId, not stored redundantly here. */
    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_phone")
    private String contactPhone;

    /** Guest contact only - never persisted for a guest with no recipient (see AppointmentWriter's notification guard). */
    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** Set only for one occurrence of a recurring series - see AppointmentSeries. */
    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "series_occurrence_index")
    private Integer seriesOccurrenceIndex;

    /** Set once AppointmentReminderScheduler writes a reminder outbox row for this appointment - prevents re-sending on every poll. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;
}
