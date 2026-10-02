package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QcRunRepository extends JpaRepository<QcRun, UUID> {

    List<QcRun> findAllByTenantIdOrderByPerformedAtDesc(UUID tenantId);

    List<QcRun> findAllByTenantIdAndInstrumentIdentifierOrderByPerformedAtDesc(UUID tenantId, String instrumentIdentifier);
}
