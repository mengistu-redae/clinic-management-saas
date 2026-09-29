package com.clinicops.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial update - only non-null fields are applied. assignedRoomId is not here - see AssetController.assignRoom. */
public record UpdateAssetRequest(
        String name,
        String serialNumber,
        LocalDate purchaseDate,
        BigDecimal purchasePrice,
        LocalDate warrantyExpiry,
        String status,
        String notes
) {
}
