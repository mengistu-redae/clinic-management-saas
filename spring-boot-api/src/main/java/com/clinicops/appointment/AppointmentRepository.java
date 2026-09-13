package com.clinicops.appointment;

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

    List<Appointment> findAllBySeriesId(UUID seriesId);

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
     * A provider's own worklist for one day - see GET /api/my-schedule.
     * Native/joined the same way as flipStaleBookedToNoShow, since
     * Appointment has no mapped relation to Slot.
     */
    @Query(value = """
            SELECT a.* FROM appointments a
            JOIN slots s ON a.slot_id = s.id
            WHERE a.tenant_id = :tenantId AND a.provider_id = :providerId
              AND s.start_time >= :dayStart AND s.start_time < :dayEnd
            ORDER BY s.start_time
            """, nativeQuery = true)
    List<Appointment> findProviderSchedule(
            @Param("tenantId") UUID tenantId,
            @Param("providerId") UUID providerId,
            @Param("dayStart") Instant dayStart,
            @Param("dayEnd") Instant dayEnd);
}
