package com.clinicops.consent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);
}
