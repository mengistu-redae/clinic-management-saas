package com.clinicops.insurance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsurancePolicyRepository extends JpaRepository<InsurancePolicy, UUID> {

    List<InsurancePolicy> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    Optional<InsurancePolicy> findByIdAndTenantId(UUID id, UUID tenantId);
}
