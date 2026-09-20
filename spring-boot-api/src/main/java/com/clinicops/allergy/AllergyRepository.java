package com.clinicops.allergy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AllergyRepository extends JpaRepository<Allergy, UUID> {

    List<Allergy> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    Optional<Allergy> findByIdAndTenantId(UUID id, UUID tenantId);
}
