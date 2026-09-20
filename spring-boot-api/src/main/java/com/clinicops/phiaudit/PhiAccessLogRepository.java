package com.clinicops.phiaudit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhiAccessLogRepository extends JpaRepository<PhiAccessLog, UUID> {

    List<PhiAccessLog> findTop200ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<PhiAccessLog> findTop200ByTenantIdAndPatientIdOrderByCreatedAtDesc(UUID tenantId, UUID patientId);
}
