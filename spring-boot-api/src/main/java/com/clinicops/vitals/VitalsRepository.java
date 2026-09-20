package com.clinicops.vitals;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VitalsRepository extends JpaRepository<Vitals, UUID> {

    Optional<Vitals> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);
}
