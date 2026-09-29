package com.clinicops.analytics;

import java.time.LocalDate;
import java.util.UUID;

/** An active stock batch expiring within the fixed 30-day look-ahead window - see StockBatchRepository.findExpiringSoon. ownerType is "medication" or "inventory_item", same vocabulary as ReorderAlert. */
public interface ExpiringBatch {
    String getOwnerType();
    UUID getOwnerId();
    String getName();
    LocalDate getExpiryDate();
    Integer getQuantityOnHand();
}
