package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * The general (non-pharmacy) stock catalog entry - clinical/medical
 * consumables, PPE & safety supplies, office/admin supplies. Deliberately
 * a *separate* entity from {@code Medication} (com.clinicops.pharmacy)
 * rather than a merged polymorphic catalog table - Medication is already
 * deeply wired into Prescription/DispenseService, and "medications are
 * one category" is achieved at the stock-tracking layer ({@link StockBatch}'s
 * own exactly-one-owner shape), not by literally merging the catalog
 * entities. Soft-deactivate only, same reasoning as Room/AppointmentType/
 * Medication - referenced by FK from StockBatch/PurchaseOrderLine with no
 * cascade.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
public class InventoryItem extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    /** clinical_supply, ppe, office_supply. */
    @Column(nullable = false)
    private String category;

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold;

    /** active, inactive. */
    @Column(nullable = false)
    private String status = "active";
}
