package com.clinicops.scheduling;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE}. The only caller - {@link
     * com.clinicops.appointment.AppointmentWriter} - reads a slot, checks
     * {@code status = 'open'}, then flips it to {@code booked}. The Redis
     * slot lock ({@code SlotLockService}) fronts this for a fast 409 on
     * concurrent requests, but this row lock is the correctness backstop:
     * without it, two transactions that both get past the Redis lock (its
     * TTL expiring mid-write, a Redis failover, a caller with locks
     * disabled) would each see {@code status = 'open'} and double-book the
     * slot. Scoped by providerId too so a caller can't accidentally act on
     * a slot belonging to a different provider than the one it validated.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Slot> findByIdAndProviderId(UUID id, UUID providerId);

    List<Slot> findAllByProviderIdAndAppointmentTypeIdAndStartTimeBetween(
            UUID providerId, UUID appointmentTypeId, Instant from, Instant to);

    List<Slot> findAllByTenantIdAndProviderIdAndAppointmentTypeIdAndStatusAndStartTimeBetween(
            UUID tenantId, UUID providerId, UUID appointmentTypeId, String status, Instant from, Instant to);

    Optional<Slot> findByProviderIdAndAppointmentTypeIdAndStartTime(UUID providerId, UUID appointmentTypeId, Instant startTime);
}
