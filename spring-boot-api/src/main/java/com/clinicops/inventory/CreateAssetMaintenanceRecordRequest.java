package com.clinicops.inventory;

import jakarta.validation.constraints.NotBlank;

public record CreateAssetMaintenanceRecordRequest(
        @NotBlank String description,
        String notes
) {
}
