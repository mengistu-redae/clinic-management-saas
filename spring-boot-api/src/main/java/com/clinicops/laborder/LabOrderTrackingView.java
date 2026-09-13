package com.clinicops.laborder;

import java.time.Instant;

/**
 * The public track-by-ref-and-phone view - deliberately narrow: status and
 * timestamps only, never result values, reference ranges, abnormal flags,
 * ID numbers, or provider names. See LabOrderService.trackByRefAndPhone.
 */
public record LabOrderTrackingView(
        String orderRef,
        String status,
        Instant orderedAt,
        Instant specimenCollectedAt,
        Instant sentAt,
        Instant resultedAt,
        Instant reviewedAt
) {
}
