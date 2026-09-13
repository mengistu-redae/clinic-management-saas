package com.clinicops.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<Encounter, UUID> {

    Optional<Encounter> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    Optional<Encounter> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Not tenant-scoped - used by "my lab orders" (a patient token carries
     * no tenant) to resolve which encounter, if any, belongs to one of the
     * patient's own appointments. Same "deliberately cross-tenant lookup
     * for an ownership-scoped path" shape as AppointmentRepository.
     * findByAppointmentRef.
     */
    Optional<Encounter> findByAppointmentId(UUID appointmentId);
}
