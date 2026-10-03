package com.clinicops.imaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImagingStudyRateRepository extends JpaRepository<ImagingStudyRate, UUID> {

    List<ImagingStudyRate> findAllByTenantId(UUID tenantId);

    Optional<ImagingStudyRate> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ImagingStudyRate> findByTenantIdAndStudyCode(UUID tenantId, String studyCode);
}
