package com.clinicops.laborder;

import java.util.List;
import java.util.UUID;

/**
 * Partial update - only non-null fields are applied, and only while
 * status = "ordered" (InvalidLabOrderStatusException otherwise). `tests`
 * is nullable-replace-the-whole-set: null = don't touch, an explicit
 * empty list is rejected (InvalidLabOrderTestsException) rather than
 * silently clearing every test.
 */
public record UpdateLabOrderRequest(
        UUID patientId,
        UUID orderingProviderId,
        String notes,
        String priority,
        List<TestItem> tests,
        boolean consentAcknowledged
) {
}
