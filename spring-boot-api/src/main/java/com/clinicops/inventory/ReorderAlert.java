package com.clinicops.inventory;

import java.util.UUID;

/** ownerType is "medication" or "inventory_item". */
public record ReorderAlert(String ownerType, UUID ownerId, String name, int currentQuantity, int reorderThreshold) {
}
