package com.clinicops.appointmenttype;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentTypeRepository extends JpaRepository<AppointmentType, UUID> {

    Optional<AppointmentType> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AppointmentType> findAllByTenantId(UUID tenantId);

    List<AppointmentType> findAllByTenantIdAndStatus(UUID tenantId, String status);
}
