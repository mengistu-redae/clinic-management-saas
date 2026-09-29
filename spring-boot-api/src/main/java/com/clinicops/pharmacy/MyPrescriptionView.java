package com.clinicops.pharmacy;

import java.time.Instant;
import java.util.UUID;

/**
 * A patient's own read-shape for a prescription (phase 33) -
 * {@code Prescription}'s own fields plus a derived
 * {@code quantityAlreadyDispensed}, computed the same way
 * {@code PrescriptionRepository.findPendingDispense} already does
 * internally for the pharmacist's queue.
 */
public record MyPrescriptionView(
        UUID id,
        UUID encounterId,
        String medicationName,
        String dosage,
        String instructions,
        String route,
        String frequency,
        String duration,
        Integer quantityPrescribed,
        Integer refillsAllowed,
        String status,
        Instant createdAt,
        long quantityAlreadyDispensed
) {
}
