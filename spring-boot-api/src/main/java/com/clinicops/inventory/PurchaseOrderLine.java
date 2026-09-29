package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a {@link PurchaseOrder} - own table, no JPA relation, same
 * "plain UUID FK + explicit repository queries, carries its own
 * tenant_id" convention as {@code JournalLine}/{@code LabOrderTest}.
 * Exactly one of {@code medicationId}/{@code inventoryItemId} is set,
 * same shape as {@link StockBatch}'s own owner columns.
 */
@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
public class PurchaseOrderLine extends BaseTenantEntity {

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "medication_id")
    private UUID medicationId;

    @Column(name = "inventory_item_id")
    private UUID inventoryItemId;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;
}
