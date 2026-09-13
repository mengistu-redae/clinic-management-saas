package com.clinicops.feepolicy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** providerId is optional (null = clinic-wide default tier) but must belong to this clinic if given - validated in FeePolicyController. */
public record CreateFeePolicyRequest(
        UUID providerId,
        @NotNull @Min(0) Integer cutoffHours,
        @NotNull @Min(0) @Max(100) Integer feePercent
) {
}
