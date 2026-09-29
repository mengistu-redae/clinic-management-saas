package com.clinicops.inventory;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One physical piece of equipment - not quantity-based, unlike
 * {@link StockBatch}. No delete endpoint at all - status transitions
 * only (in_service/under_maintenance/retired/disposed), same "real
 * inventory/audit weight" precedent as {@code Allergy}/{@code Medication}.
 * {@code assignedRoomId} (nullable FK to {@code com.clinicops.room.Room})
 * is deliberately not part of the generic partial update - it needs real
 * null-clear semantics, so it's set only via the dedicated
 * {@code AssetController.assignRoom} action endpoint.
 */
@Entity
@Table(name = "assets")
@Getter
@Setter
public class Asset extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "purchase_price")
    private BigDecimal purchasePrice;

    @Column(name = "warranty_expiry")
    private LocalDate warrantyExpiry;

    /** in_service, under_maintenance, retired, disposed. */
    @Column(nullable = false)
    private String status = "in_service";

    @Column(name = "assigned_room_id")
    private UUID assignedRoomId;

    private String notes;
}
