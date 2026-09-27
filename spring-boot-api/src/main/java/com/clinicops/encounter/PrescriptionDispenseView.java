package com.clinicops.encounter;

import java.time.Instant;
import java.util.UUID;

/**
 * The pharmacy dispense queue's own read-shape - a Prescription joined
 * through its Encounter/Appointment for a real patientId/contactName
 * (same "join since there's no JPA relation" reasoning as
 * AppointmentWorklistView), plus quantityAlreadyDispensed (a live
 * subquery sum over dispense_records, computed in the query itself - same
 * "compute the running total in a query, not a cached column" shape
 * RefundRepository.sumAmountByPaymentId already established). Lives here,
 * alongside Prescription/PrescriptionRepository, not in
 * com.clinicops.pharmacy - same "the projection lives with its entity's
 * repository" precedent AppointmentWorklistView already set.
 */
public interface PrescriptionDispenseView {
    UUID getId();

    UUID getEncounterId();

    UUID getPatientId();

    String getContactName();

    /** Null for a guest booking (no patientId) - the caller falls back to getContactName(). */
    String getPatientName();

    String getMedicationName();

    String getDosage();

    String getInstructions();

    String getRoute();

    String getFrequency();

    String getDuration();

    /** The prescribed total (Prescription.quantityDispensed - an existing, somewhat confusingly-named field predating this module), null if the provider never set one. */
    Integer getQuantityPrescribed();

    Integer getRefillsAllowed();

    String getStatus();

    Instant getCreatedAt();

    Long getQuantityAlreadyDispensed();
}
