package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;

public record CreateSupplierRequest(
        @NotBlank String name,
        String contactName,
        String phone,
        String email
) {
}
