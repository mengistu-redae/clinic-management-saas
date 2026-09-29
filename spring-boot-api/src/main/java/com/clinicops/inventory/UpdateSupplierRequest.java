package com.clinicops.inventory;

/** Partial update - only non-null fields are applied. */
public record UpdateSupplierRequest(
        String name,
        String contactName,
        String phone,
        String email,
        String status
) {
}
