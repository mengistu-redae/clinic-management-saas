package com.clinicops.appointment;

import com.clinicops.analytics.DailyCount;
import com.clinicops.analytics.ProviderAppointmentCount;
import com.clinicops.analytics.StatusCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    List<Appointment> findAllByTenantId(UUID tenantId);

    /**
     * Staff-worklist counterpart of findAllByTenantId - joined with slots
     * for a real startTime/endTime plus contactName/contactPhone, so a
     * dashboard's own "recent appointments" widget can show who and when
     * without a second per-row lookup for a guest booking - see
     * AppointmentWorklistView's own javadoc. Backs
     * GET /api/appointments/worklist; the plain tenant-wide GET
     * /api/appointments stays untouched (bare Appointment, used by more
     * call sites than just this one dashboard widget).
     */
    @Query(value = """
            SELECT a.id as id, a.tenant_id as tenantId, a.patient_id as patientId,
                   a.provider_id as providerId, a.appointment_type_id as appointmentTypeId,
                   a.channel as channel, a.status as status, a.appointment_ref as appointmentRef,
                   a.clinic_ref as clinicRef, a.contact_name as contactName, a.contact_phone as contactPhone,
                   s.start_time as startTime, s.end_time as endTime,
                   a.booked_at as bookedAt, a.cancelled_at as cancelledAt, a.cancellation_reason as cancellationReason
            FROM appointments a JOIN slots s ON a.slot_id = s.id
            WHERE a.tenant_id = :tenantId
            ORDER BY a.booked_at DESC
            """, nativeQuery = true)
    List<AppointmentWorklistView> findAllByTenantIdWithSlot(@Param("tenantId") UUID tenantId);

    Optional<Appointment> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Appointment> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    boolean existsByAppointmentRef(String appointmentRef);

    boolean existsByTenantIdAndClinicRef(UUID tenantId, String clinicRef);

    /**
     * Public track-by-ref lookup - not tenant-scoped, since the caller has
     * no tenant at all; the ref + phone match done by the caller stands in
     * for an ownership check, same as the reference project's
     * BookingRepository.findByBookingRef.
     */
    Optional<Appointment> findByAppointmentRef(String appointmentRef);

    // ---- ownership-scoped (patient_portal "my appointments"): a patient
    // token carries no tenant, so these are scoped by customerUserId (the
    // resolved AppUser.id) instead, same shape as the reference project's
    // Booking.customerUserId. ----

    List<Appointment> findAllByCustomerUserId(UUID customerUserId);

    Optional<Appointment> findByIdAndCustomerUserId(UUID id, UUID customerUserId);

    /**
     * Same ownership scope as findAllByCustomerUserId, but joined with
     * slots for a real startTime/endTime - see AppointmentWithSlotView's
     * javadoc for why the plain entity isn't enough here. Column aliases
     * match the projection's getter names exactly (Spring Data's native
     * -query projection binding).
     */
    @Query(value = """
            SELECT a.id as id, a.tenant_id as tenantId, a.patient_id as patientId,
                   a.provider_id as providerId, a.appointment_type_id as appointmentTypeId,
                   a.channel as channel, a.status as status, a.appointment_ref as appointmentRef,
                   a.clinic_ref as clinicRef, s.start_time as startTime, s.end_time as endTime,
                   a.booked_at as bookedAt, a.cancelled_at as cancelledAt, a.cancellation_reason as cancellationReason
            FROM appointments a JOIN slots s ON a.slot_id = s.id
            WHERE a.customer_user_id = :customerUserId
            ORDER BY s.start_time DESC
            """, nativeQuery = true)
    List<AppointmentWithSlotView> findAllByCustomerUserIdWithSlot(@Param("customerUserId") UUID customerUserId);

    /** Single-appointment counterpart of findAllByCustomerUserIdWithSlot - see GET /api/my-appointments/{id}. */
    @Query(value = """
            SELECT a.id as id, a.tenant_id as tenantId, a.patient_id as patientId,
                   a.provider_id as providerId, a.appointment_type_id as appointmentTypeId,
                   a.channel as channel, a.status as status, a.appointment_ref as appointmentRef,
                   a.clinic_ref as clinicRef, s.start_time as startTime, s.end_time as endTime,
                   a.booked_at as bookedAt, a.cancelled_at as cancelledAt, a.cancellation_reason as cancellationReason
            FROM appointments a JOIN slots s ON a.slot_id = s.id
            WHERE a.id = :id AND a.customer_user_id = :customerUserId
            """, nativeQuery = true)
    Optional<AppointmentWithSlotView> findByIdAndCustomerUserIdWithSlot(@Param("id") UUID id, @Param("customerUserId") UUID customerUserId);

    /** Tenant-scoped, not a bare findAllBySeriesId - same "explicit tenant id, never an implicit read" convention as every other staff-scoped query here, even though a series' own occurrences never span tenants in practice. Backs the "cancel this and the rest of the series" action. */
    List<Appointment> findAllByTenantIdAndSeriesId(UUID tenantId, UUID seriesId);

    /**
     * Bulk-flips every still-`booked` appointment whose slot has fully
     * elapsed to `no_show` - see NoShowScheduler. Purely worklist hygiene;
     * the live check-in gate (CheckInService.checkIn) never depends on this
     * having run. Native/JPQL-with-a-join since Appointment has no mapped
     * relation to Slot (this codebase uses plain UUID FK columns
     * everywhere, not JPA associations).
     */
    @Modifying
    @Query(value = """
            UPDATE appointments a SET status = 'no_show'
            FROM slots s
            WHERE a.slot_id = s.id AND a.status = 'booked' AND s.end_time < :now
            """, nativeQuery = true)
    int flipStaleBookedToNoShow(@Param("now") Instant now);

    /**
     * Patient-engagement SMS reminders (sketched 2026-10-04) - every still
     * -`booked` appointment whose slot starts within the reminder window
     * and hasn't been reminded yet. One-sided (`start_time > :now AND
     * start_time <= :cutoff`), not a tight poll-interval slice, so a
     * delayed run or a missed cycle never skips an appointment -
     * `reminder_sent_at` is what actually prevents a duplicate, the same
     * "a flag gates the real invariant, the query window is just a net"
     * reasoning `flipStaleBookedToNoShow`'s own one-sided `< :now` already
     * uses. LEFT JOIN to patients for a portal/front-desk booking's own
     * phone - a guest booking has no patients row at all, falls back to
     * the appointment's own contact_phone in AppointmentReminderScheduler.
     */
    @Query(value = """
            SELECT a.id as id, a.tenant_id as tenantId, a.appointment_ref as appointmentRef,
                   a.contact_phone as contactPhone, p.phone as patientPhone, s.start_time as startTime
            FROM appointments a
            JOIN slots s ON a.slot_id = s.id
            LEFT JOIN patients p ON a.patient_id = p.id
            WHERE a.status = 'booked' AND a.reminder_sent_at IS NULL
              AND s.start_time > :now AND s.start_time <= :cutoff
            """, nativeQuery = true)
    List<AppointmentReminderCandidate> findReminderCandidates(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /**
     * A provider's own worklist for one day - see GET /api/my-schedule.
     * Returns AppointmentWorklistView (not bare Appointment) so the
     * dashboard can show a real startTime and a guest's contactName
     * without a second lookup - a real gap found in UI review (the list
     * previously showed only a reference + status, nothing telling a
     * provider who's next or in what order beyond the query's own
     * ORDER BY). Same join shape flipStaleBookedToNoShow already uses,
     * since Appointment has no mapped relation to Slot.
     */
    @Query(value = """
            SELECT a.id as id, a.tenant_id as tenantId, a.patient_id as patientId,
                   a.provider_id as providerId, a.appointment_type_id as appointmentTypeId,
                   a.channel as channel, a.status as status, a.appointment_ref as appointmentRef,
                   a.clinic_ref as clinicRef, a.contact_name as contactName, a.contact_phone as contactPhone,
                   s.start_time as startTime, s.end_time as endTime,
                   a.booked_at as bookedAt, a.cancelled_at as cancelledAt, a.cancellation_reason as cancellationReason
            FROM appointments a
            JOIN slots s ON a.slot_id = s.id
            WHERE a.tenant_id = :tenantId AND a.provider_id = :providerId
              AND s.start_time >= :dayStart AND s.start_time < :dayEnd
            ORDER BY s.start_time
            """, nativeQuery = true)
    List<AppointmentWorklistView> findProviderSchedule(
            @Param("tenantId") UUID tenantId,
            @Param("providerId") UUID providerId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);

    // ---- clinic-admin analytics dashboard (frontend phase O) - see ClinicAnalyticsController ----

    /**
     * Appointments booked per calendar day since `since` - a booking
     * -activity trend, not a schedule-density one (deliberately keyed off
     * bookedAt, not the slot's own start time, so this needs no join to
     * slots). Day boundaries are UTC, same as every other day-math in this
     * app (SlotGenerator/AvailabilityController/my-schedule) - this app has
     * no per-clinic timezone concept yet.
     */
    @Query(value = """
            SELECT CAST(booked_at AS date) AS day, COUNT(*) AS total
            FROM appointments
            WHERE tenant_id = :tenantId AND booked_at >= :since
            GROUP BY CAST(booked_at AS date)
            ORDER BY day
            """, nativeQuery = true)
    List<DailyCount> findDailyAppointmentVolume(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    /** Current snapshot, not time-windowed - every appointment this tenant has, grouped by its live status. */
    @Query("SELECT a.status AS status, COUNT(a) AS total FROM Appointment a WHERE a.tenantId = :tenantId GROUP BY a.status")
    List<StatusCount> countByStatus(@Param("tenantId") UUID tenantId);

    /** Appointment volume per provider since `since` - the utilization panel; the frontend resolves providerId to a name via its own already-fetched provider list. */
    @Query("SELECT a.providerId AS providerId, COUNT(a) AS total FROM Appointment a WHERE a.tenantId = :tenantId AND a.bookedAt >= :since GROUP BY a.providerId")
    List<ProviderAppointmentCount> countByProviderSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);
}
