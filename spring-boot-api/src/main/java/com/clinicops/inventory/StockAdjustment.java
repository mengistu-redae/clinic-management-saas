package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A generic, staff-initiated, append-only correction against a
 * {@link StockBatch} owned by either a Medication or an InventoryItem -
 * non-medication stock has no Prescription driving its consumption the
 * way DispenseRecord does, so this closes that gap. Same audit-row shape
 * as DispenseRecord - no update/delete anywhere.
 */
@Entity
@Table(name = "stock_adjustments")
@Getter
@Setter
public class StockAdjustment extends BaseTenantEntity {

    @Column(name = "stock_batch_id", nullable = false)
    private UUID stockBatchId;

    /** Negative for usage/waste, positive for a correction. */
    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    /** used, wasted, expired, correction, other. */
    @Column(nullable = false)
    private String reason;

    @Column(name = "adjusted_by")
    private UUID adjustedBy;

    private String notes;
}
