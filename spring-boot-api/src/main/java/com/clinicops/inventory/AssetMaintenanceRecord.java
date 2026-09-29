package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A simple append-only maintenance log entry for an {@link Asset} - no
 * update/delete anywhere. Deliberately no due-date/reminder field: a
 * history to look back on, not a second scheduling system alongside
 * appointments (the user's own pinned scope boundary).
 */
@Entity
@Table(name = "asset_maintenance_records")
@Getter
@Setter
public class AssetMaintenanceRecord extends BaseTenantEntity {

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt = Instant.now();

    @Column(nullable = false)
    private String description;

    @Column(name = "performed_by")
    private UUID performedBy;

    private String notes;
}
