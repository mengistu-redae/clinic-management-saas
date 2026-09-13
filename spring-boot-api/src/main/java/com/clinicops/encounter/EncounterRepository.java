package com.clinicops.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    Optional<Encounter> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);
}
