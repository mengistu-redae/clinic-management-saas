package com.clinicops.medicalhistory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, UUID> {

    Optional<MedicalHistory> findByPatientIdAndTenantId(UUID patientId, UUID tenantId);
}
