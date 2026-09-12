package com.clinicops.appointment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A bounded recurring-appointment series (e.g. "every 2 weeks x6") - staff
 * -only, decided in plan mode as a deliberate expansion of phase 2's
 * original scope. Each occurrence is its own real {@link Appointment} row,
 * booked through the exact same single-slot lock+write path as any other
 * appointment - see AppointmentSeriesService. This row is just the series'
 * own configuration plus an idempotency key for the whole creation request.
 */
@Entity
@Table(name = "appointment_series")
@Getter
@Setter
public class AppointmentSeries {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "appointment_type_id", nullable = false)
    private UUID appointmentTypeId;

    @Column(nullable = false)
    private String channel;

    @Column(name = "interval_weeks", nullable = false)
    private int intervalWeeks;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
