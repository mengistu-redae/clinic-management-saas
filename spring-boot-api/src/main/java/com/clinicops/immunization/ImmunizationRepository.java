package com.clinicops.immunization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImmunizationRepository extends JpaRepository<Immunization, UUID> {

    List<Immunization> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    Optional<Immunization> findByIdAndTenantId(UUID id, UUID tenantId);
}
