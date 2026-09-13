package com.clinicops.feepolicy;

/** Partial update - only non-null fields are applied. providerId isn't editable here - delete and recreate the tier to move it to a different provider. */
public record UpdateFeePolicyRequest(Integer cutoffHours, Integer feePercent) {
}
