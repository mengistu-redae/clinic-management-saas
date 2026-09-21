package com.clinicops.encounter;

import jakarta.validation.constraints.NotBlank;

/**
 * dosage/instructions/route/frequency/duration/quantityDispensed/
 * refillsAllowed/status are all optional - status defaults to "active"
 * when omitted (see EncounterService.replacePrescriptions); route and
 * status are both allow-listed there too. Phase 11 fields, see
 * Prescription's own javadoc.
 */
public record PrescriptionInput(
        @NotBlank String medicationName,
        String dosage,
        String instructions,
        String route,
        String frequency,
        String duration,
        Integer quantityDispensed,
        Integer refillsAllowed,
        String status
) {
}
