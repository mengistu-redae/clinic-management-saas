package com.clinicops.labrate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabTestRateRepository extends JpaRepository<LabTestRate, UUID> {

    Optional<LabTestRate> findByTenantIdAndTestCode(UUID tenantId, String testCode);

    List<LabTestRate> findAllByTenantId(UUID tenantId);

    Optional<LabTestRate> findByIdAndTenantId(UUID id, UUID tenantId);
}
