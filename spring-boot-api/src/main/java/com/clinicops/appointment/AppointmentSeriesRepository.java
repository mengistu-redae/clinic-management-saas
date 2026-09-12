package com.clinicops.appointment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppointmentSeriesRepository extends JpaRepository<AppointmentSeries, UUID> {

    Optional<AppointmentSeries> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
}
