package com.clinicops.pharmacy;

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
 * One received lot of a {@link Medication} - `quantityReceived` is the
 * original amount (so "consumed" is derivable as received-minus-on-hand
 * with no separate ledger); `quantityOnHand` is decremented by
 * {@link DispenseService} and auto-flips this batch to {@code depleted}
 * at zero. No delete endpoint at all - status transitions only
 * (active/depleted/expired/recalled), same "real inventory/audit weight"
 * precedent as {@code Allergy}.
 */
@Entity
@Table(name = "stock_batches")
@Getter
@Setter
public class StockBatch extends BaseTenantEntity {

    @Column(name = "medication_id", nullable = false)
    private UUID medicationId;

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
