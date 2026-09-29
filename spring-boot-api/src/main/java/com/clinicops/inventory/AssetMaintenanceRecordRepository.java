package com.clinicops.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetMaintenanceRecordRepository extends JpaRepository<AssetMaintenanceRecord, UUID> {

    List<AssetMaintenanceRecord> findAllByAssetIdAndTenantId(UUID assetId, UUID tenantId);
}
