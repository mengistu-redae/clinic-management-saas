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
 * A real order placed with a {@link Supplier} - immutable once placed
 * except for its own status transitions (ordered -> received/cancelled),
 * same "a placed order is a real document" reasoning {@code Invoice}/
 * {@code LabOrder} already use for their own line items. Receiving is
 * all-or-nothing (no partial receipt) in v1.
 */
@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
public class PurchaseOrder extends BaseTenantEntity {

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    /** ordered, received, cancelled. */
    @Column(nullable = false)
    private String status = "ordered";

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt = Instant.now();

    @Column(name = "received_at")
    private Instant receivedAt;

    private String notes;
}
