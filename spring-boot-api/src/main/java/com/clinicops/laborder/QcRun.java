package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lab module L4 - a single control-material run logged against one
 * instrument on one day, genuinely append-only (no update/delete
 * anywhere), same shape AssetMaintenanceRecord/StockAdjustment already
 * use for this kind of audit-weight log entry. {@code instrumentIdentifier}
 * is free text in v1 - there's nothing to integrate with in this dev
 * environment, matching L6's own "integration-readiness, not a real
 * integration" scope boundary.
 *
 * {@code pass} is computed server-side from {@code observedValue} against
 * {@code expectedRangeLow}/{@code expectedRangeHigh} - flag-only, the
 * pinned fork's recommended option (2026-10-02): a failed run is visible
 * here but never blocks new result entry anywhere else in this app.
 */
@Entity
@Table(name = "lab_qc_runs")
@Getter
@Setter
public class QcRun extends BaseTenantEntity {

    @Column(name = "instrument_identifier", nullable = false)
    private String instrumentIdentifier;

    @Column(name = "analyte_name", nullable = false)
    private String analyteName;

    @Column(name = "control_material_lot", nullable = false)
    private String controlMaterialLot;

    @Column(name = "expected_range_low", nullable = false)
    private BigDecimal expectedRangeLow;

    @Column(name = "expected_range_high", nullable = false)
    private BigDecimal expectedRangeHigh;

    @Column(name = "observed_value", nullable = false)
    private String observedValue;

    @Column(nullable = false)
    private Boolean pass;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt = Instant.now();

    @Column(name = "performed_by")
    private UUID performedBy;
}
