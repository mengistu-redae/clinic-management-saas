package com.clinicops.pharmacy;

import java.time.Instant;
import java.util.UUID;

/**
 * The pharmacy dispense queue's own read-shape (phase 32) - carries
 * every field {@code PrescriptionDispenseView} already exposes (same
 * component names, so Jackson serializes byte-identical JSON for all
 * pre-existing fields - purely additive, not a breaking reshape) plus
 * {@code suggestedMedicationId}, a deliberately simple bidirectional
 * substring match against the tenant's active catalog - not real fuzzy
 * matching, same "keep minimal in v1" convention ICD-10/Prescription
 * .route already use. A real match still has to be explicitly confirmed
 * by the pharmacist.
 */
public record PrescriptionQueueEntry(
        UUID id,
        UUID encounterId,
        UUID patientId,
        String contactName,
        String patientName,
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
        Long quantityAlreadyDispensed,
        UUID suggestedMedicationId
) {
}
