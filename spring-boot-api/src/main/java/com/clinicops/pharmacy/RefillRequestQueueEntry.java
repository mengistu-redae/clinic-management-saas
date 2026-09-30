package com.clinicops.pharmacy;

import java.time.Instant;
import java.util.UUID;

/**
 * The staff refill-request queue's own read shape (phase 33 backend,
 * phase 39 frontend) - {@link PrescriptionRefillRequest}'s own fields
 * plus a resolved {@code patientName}/{@code medicationName}, closing a
 * real gap: unlike every other pharmacy worklist in this app, nothing
 * let a pharmacist resolve either id to a name before this (
 * {@code PatientController}'s read gate excludes {@code pharmacist},
 * and a refill request is typically for a prescription that has
 * already dropped off {@code GET /api/pharmacy/queue}). Resolved inline
 * in {@link PatientPrescriptionController}, same "controller composes
 * repositories directly for simple read-only aggregation" precedent
 * {@code DispenseController.queue()} already sets - {@code null} when
 * either lookup misses, same graceful-miss convention every other
 * embedded-name field in this app already uses.
 */
public record RefillRequestQueueEntry(
        UUID id,
        UUID prescriptionId,
        UUID patientId,
        String patientName,
        String medicationName,
        UUID requestedBy,
        Instant createdAt,
        String notes,
        String status,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewNotes
) {
}
