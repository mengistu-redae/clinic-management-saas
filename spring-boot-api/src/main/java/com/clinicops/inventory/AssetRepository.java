package com.clinicops.inventory;

import com.clinicops.analytics.StatusCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findAllByTenantId(UUID tenantId);

    List<Asset> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Asset> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Phase 34 - current snapshot, not time-windowed, same shape as AppointmentRepository.countByStatus. */
    @Query("SELECT a.status AS status, COUNT(a) AS total FROM Asset a WHERE a.tenantId = :tenantId GROUP BY a.status")
    List<StatusCount> countByStatus(@Param("tenantId") UUID tenantId);
}
