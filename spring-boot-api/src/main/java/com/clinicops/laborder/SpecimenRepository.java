package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpecimenRepository extends JpaRepository<Specimen, UUID> {

    List<Specimen> findAllByLabOrderId(UUID labOrderId);

    Optional<Specimen> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Full-replace semantics when an order's own tests are replaced - see SpecimenService.deriveForOrder. */
    void deleteAllByLabOrderId(UUID labOrderId);
}
