package com.clinicops.inventory;

import java.util.List;

/** Same "wrapper record for one always-together response" shape as JournalEntryWithLines. */
public record PurchaseOrderWithLines(PurchaseOrder order, List<PurchaseOrderLine> lines) {
}
