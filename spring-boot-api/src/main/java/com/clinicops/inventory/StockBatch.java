package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One received lot of either a {@code Medication} (com.clinicops.pharmacy)
 * or an {@link InventoryItem} - exactly one of {@code medicationId}/
 * {@code inventoryItemId} is set (DB CHECK, same "exactly one owner"
 * shape {@code Payment}/{@code Invoice} already use). Phase 29 -
 * generalized and relocated here from com.clinicops.pharmacy, its
 * natural home once it serves two domains, the same reasoning
 * {@code Payment} lives in its own package rather than under
 * {@code appointment}/{@code laborder}.
 *
 * `quantityReceived` is the original amount (so "consumed" is derivable
 * as received-minus-on-hand with no separate ledger); `quantityOnHand`
 * is decremented by {@code DispenseService} (medication-owned batches)
 * or a {@link StockAdjustment} (either owner) and auto-flips this batch
 * to {@code depleted} at zero. No delete endpoint at all - status
 * transitions only (active/depleted/expired/recalled), same "real
 * inventory/audit weight" precedent as {@code Allergy}.
 */
@Entity
@Table(name = "stock_batches")
@Getter
@Setter
public class StockBatch extends BaseTenantEntity {

    @Column(name = "medication_id")
    private UUID medicationId;

    @Column(name = "inventory_item_id")
    private UUID inventoryItemId;

    @Column(name = "batch_number")
    private String batchNumber;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    /** active, depleted, expired, recalled. */
    @Column(nullable = false)
    private String status = "active";

    @Column(name = "write_off_reason")
    private String writeOffReason;
}
