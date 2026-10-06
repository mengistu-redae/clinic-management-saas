package com.clinicops.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    /** Phase 45: group-aware read, used once patient ownership is already proven (own clinic or same clinic group). */
    List<ConsentRecord> findAllByPatientId(UUID patientId);
}
