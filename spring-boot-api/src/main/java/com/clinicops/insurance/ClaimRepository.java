package com.clinicops.insurance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {

    List<Claim> findAllByInvoiceIdAndTenantId(UUID invoiceId, UUID tenantId);

    List<Claim> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    Optional<Claim> findByIdAndTenantId(UUID id, UUID tenantId);
}
