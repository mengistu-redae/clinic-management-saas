package com.clinicops.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionRefillRequestRepository extends JpaRepository<PrescriptionRefillRequest, UUID> {

    List<PrescriptionRefillRequest> findAllByRequestedBy(UUID requestedBy);

    List<PrescriptionRefillRequest> findAllByTenantId(UUID tenantId);

    List<PrescriptionRefillRequest> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<PrescriptionRefillRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByPrescriptionIdAndStatus(UUID prescriptionId, String status);
}
