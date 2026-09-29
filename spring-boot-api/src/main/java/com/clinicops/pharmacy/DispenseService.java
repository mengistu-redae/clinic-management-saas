package com.clinicops.pharmacy;

import com.clinicops.allergy.Allergy;
import com.clinicops.allergy.AllergyRepository;
import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - same "not a contended
 * resource" reasoning EncounterService already gives for skipping the
 * Redis-lock split-bean pattern (SlotLockService/AppointmentWriter): two
 * pharmacists dispensing from the exact same batch at the exact same
 * instant is a low-risk race this app doesn't guard against anywhere else
 * at this level either.
 *
 * Phase 27 - clinical safety checks run right after the medication is
 * resolved, before any stock-batch/quantity work: an allergy cross-check
 * against the patient's own active {@code Allergy} rows, and a
 * drug-interaction cross-check against the patient's other active
 * prescriptions via the self-maintained {@link DrugInteractionPair}
 * table. Both are substring-matching against free text (the same "keep
 * minimal, no drug-class ontology" limitation this app already accepts
 * for ICD-10) - not real clinical-grade matching. Neither hard-blocks: an
 * unacknowledged conflict throws {@link ClinicalSafetyConflictException};
 * {@code request.acknowledgeConflict()} lets it through, and only then is
 * {@code DispenseRecord.safetyOverrideAcknowledged} set true - never just
 * because the flag was sent with nothing to override. A guest-channel
 * prescription (no {@code patientId} on its appointment) has no allergy
 * data and no other prescriptions to check, so both checks silently skip
 * - a deliberate limitation, not an oversight.
 *
 * Phase 28 - a {@link Medication} carrying a non-null
 * {@code controlledSubstanceSchedule} can no longer be dispensed via the
 * plain {@link #dispense} path at all; it goes through
 * {@link #requestControlledSubstanceDispense}/{@link #coSignControlledSubstanceDispense}
 * instead - a separate, mutable two-phase workflow ({@link PendingControlledSubstanceDispense})
 * requiring a *different* pharmacist/clinic_admin to co-sign before the
 * real stock decrement/{@link DispenseRecord} happens. {@link #requireValidBatch}/
 * {@link #requireWithinPrescribedTotal}/{@link #finalizeDispense} are the
 * shared tail end both paths call - re-run for real at cosign time since
 * stock can move between the request and the cosign.
 */
@Service
public class DispenseService {

    private final PrescriptionRepository prescriptionRepository;
    private final MedicationRepository medicationRepository;
    private final StockBatchRepository stockBatchRepository;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final AllergyRepository allergyRepository;
    private final DrugInteractionPairRepository drugInteractionPairRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final PendingControlledSubstanceDispenseRepository pendingControlledSubstanceDispenseRepository;

    public DispenseService(
            PrescriptionRepository prescriptionRepository,
            MedicationRepository medicationRepository,
            StockBatchRepository stockBatchRepository,
            DispenseRecordRepository dispenseRecordRepository,
            AllergyRepository allergyRepository,
            DrugInteractionPairRepository drugInteractionPairRepository,
            EncounterRepository encounterRepository,
            AppointmentRepository appointmentRepository,
            PendingControlledSubstanceDispenseRepository pendingControlledSubstanceDispenseRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.medicationRepository = medicationRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.allergyRepository = allergyRepository;
        this.drugInteractionPairRepository = drugInteractionPairRepository;
        this.encounterRepository = encounterRepository;
        this.appointmentRepository = appointmentRepository;
        this.pendingControlledSubstanceDispenseRepository = pendingControlledSubstanceDispenseRepository;
    }

    @Transactional
    public DispenseRecord dispense(UUID prescriptionId, UUID tenantId, DispenseRequest request, UUID dispensedBy) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + prescriptionId));

        Medication medication = medicationRepository.findByIdAndTenantId(request.medicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + request.medicationId()));
        if (medication.getControlledSubstanceSchedule() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This medication is a controlled substance - use the controlled-substance request flow instead");
        }

        boolean conflictOverridden = checkClinicalSafety(tenantId, prescription, medication, request.acknowledgeConflict());
        requireValidBatch(request.stockBatchId(), tenantId, request.medicationId(), request.quantity());
        requireWithinPrescribedTotal(prescription, request.quantity());

        return finalizeDispense(tenantId, prescriptionId, request.stockBatchId(), request.quantity(),
                request.notes(), conflictOverridden, dispensedBy, null);
    }

    /**
     * Phase 28 - a pharmacist's initial request for a controlled
     * -substance dispense. Captures intent only: the clinical safety
     * check runs now (same as the plain path - this is where the real
     * "does this look safe, and do I acknowledge it" judgment call
     * belongs, not deferred to whoever co-signs later), and both
     * validation helpers run read-only - no stock decrement yet.
     */
    @Transactional
    public PendingControlledSubstanceDispense requestControlledSubstanceDispense(
            UUID prescriptionId, UUID tenantId, RequestControlledSubstanceDispenseRequest request, UUID requestedBy) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + prescriptionId));

        Medication medication = medicationRepository.findByIdAndTenantId(request.medicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + request.medicationId()));
        if (medication.getControlledSubstanceSchedule() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This medication is not a controlled substance - use the plain dispense endpoint instead");
        }

        boolean conflictOverridden = checkClinicalSafety(tenantId, prescription, medication, request.acknowledgeConflict());
        requireValidBatch(request.stockBatchId(), tenantId, request.medicationId(), request.quantity());
        requireWithinPrescribedTotal(prescription, request.quantity());

        PendingControlledSubstanceDispense pending = new PendingControlledSubstanceDispense();
        pending.setTenantId(tenantId);
        pending.setPrescriptionId(prescriptionId);
        pending.setMedicationId(request.medicationId());
        pending.setStockBatchId(request.stockBatchId());
        pending.setQuantity(request.quantity());
        pending.setNotes(request.notes());
        pending.setSafetyOverrideAcknowledged(conflictOverridden);
        pending.setRequestedBy(requestedBy);
        return pendingControlledSubstanceDispenseRepository.save(pending);
    }

    /**
     * A *different* pharmacist/clinic_admin than the requester co-signs -
     * the one real gate this whole phase exists for. Both validation
     * helpers re-run for real here (stock/prescribed-total may have
     * moved since the request was made) before the actual stock
     * decrement/DispenseRecord happens.
     */
    @Transactional
    public PendingControlledSubstanceDispense coSignControlledSubstanceDispense(UUID pendingId, UUID tenantId, UUID coSignedBy) {
        PendingControlledSubstanceDispense pending = pendingControlledSubstanceDispenseRepository.findByIdAndTenantId(pendingId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Controlled-substance request not found: " + pendingId));
        if (!"pending".equals(pending.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been " + pending.getStatus());
        }
        if (coSignedBy.equals(pending.getRequestedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A different pharmacist or clinic_admin must co-sign this request");
        }

        requireValidBatch(pending.getStockBatchId(), tenantId, pending.getMedicationId(), pending.getQuantity());
        Prescription prescription = prescriptionRepository.findById(pending.getPrescriptionId())
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + pending.getPrescriptionId()));
        requireWithinPrescribedTotal(prescription, pending.getQuantity());

        DispenseRecord record = finalizeDispense(tenantId, pending.getPrescriptionId(), pending.getStockBatchId(), pending.getQuantity(),
                pending.getNotes(), pending.isSafetyOverrideAcknowledged(), pending.getRequestedBy(), coSignedBy);

        pending.setStatus("cosigned");
        pending.setCoSignedBy(coSignedBy);
        pending.setCoSignedAt(Instant.now());
        pending.setDispenseRecordId(record.getId());
        return pendingControlledSubstanceDispenseRepository.save(pending);
    }

    /** No stock touched - it was never decremented at request time. */
    @Transactional
    public PendingControlledSubstanceDispense rejectControlledSubstanceDispense(UUID pendingId, UUID tenantId, UUID rejectedBy, String reason) {
        PendingControlledSubstanceDispense pending = pendingControlledSubstanceDispenseRepository.findByIdAndTenantId(pendingId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Controlled-substance request not found: " + pendingId));
        if (!"pending".equals(pending.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This request has already been " + pending.getStatus());
        }
        pending.setStatus("rejected");
        pending.setRejectedBy(rejectedBy);
        pending.setRejectedAt(Instant.now());
        pending.setRejectionReason(reason);
        return pendingControlledSubstanceDispenseRepository.save(pending);
    }

    /** The picked batch must belong to the picked medication (400) and have enough quantityOnHand (409). Read-only - no side effects. */
    private void requireValidBatch(UUID stockBatchId, UUID tenantId, UUID medicationId, int quantity) {
        StockBatch batch = stockBatchRepository.findByIdAndTenantId(stockBatchId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + stockBatchId));
        if (!batch.getMedicationId().equals(medicationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This stock batch does not belong to the given medication");
        }
        if (batch.getQuantityOnHand() < quantity) {
            throw new InsufficientStockException(
                    "Only " + batch.getQuantityOnHand() + " on hand in this batch, " + quantity + " requested");
        }
    }

    /** Only checked when the prescription itself has a prescribed total set. Read-only - no side effects. */
    private void requireWithinPrescribedTotal(Prescription prescription, int quantity) {
        if (prescription.getQuantityDispensed() == null) {
            return;
        }
        long alreadyDispensed = dispenseRecordRepository.sumQuantityByPrescriptionId(prescription.getId());
        if (alreadyDispensed + quantity > prescription.getQuantityDispensed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This would exceed the prescribed quantity (" + prescription.getQuantityDispensed()
                            + ", " + alreadyDispensed + " already dispensed)");
        }
    }

    /** The shared tail end both the plain and controlled-substance paths call: re-loads and decrements the batch, then builds and saves the real DispenseRecord. */
    private DispenseRecord finalizeDispense(UUID tenantId, UUID prescriptionId, UUID stockBatchId, int quantity,
            String notes, boolean safetyOverrideAcknowledged, UUID dispensedBy, UUID coSignedBy) {
        StockBatch batch = stockBatchRepository.findByIdAndTenantId(stockBatchId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + stockBatchId));
        batch.setQuantityOnHand(batch.getQuantityOnHand() - quantity);
        if (batch.getQuantityOnHand() == 0) {
            batch.setStatus("depleted");
        }
        stockBatchRepository.save(batch);

        DispenseRecord record = new DispenseRecord();
        record.setTenantId(tenantId);
        record.setPrescriptionId(prescriptionId);
        record.setMedicationId(batch.getMedicationId());
        record.setStockBatchId(stockBatchId);
        record.setQuantityDispensed(quantity);
        record.setDispensedBy(dispensedBy);
        record.setNotes(notes);
        record.setSafetyOverrideAcknowledged(safetyOverrideAcknowledged);
        record.setCoSignedBy(coSignedBy);
        return dispenseRecordRepository.save(record);
    }

    /** @return true if a real conflict was found and the caller acknowledged it (for DispenseRecord.safetyOverrideAcknowledged). */
    private boolean checkClinicalSafety(UUID tenantId, Prescription prescription, Medication medication, boolean acknowledgeConflict) {
        UUID patientId = resolvePatientId(prescription);
        if (patientId == null) {
            return false;
        }

        List<String> conflicts = new ArrayList<>();
        conflicts.addAll(findAllergyConflicts(patientId, tenantId, medication));
        conflicts.addAll(findInteractionConflicts(patientId, tenantId, prescription.getId(), medication));

        if (conflicts.isEmpty()) {
            return false;
        }
        if (!acknowledgeConflict) {
            throw new ClinicalSafetyConflictException(String.join("; ", conflicts));
        }
        return true;
    }

    private UUID resolvePatientId(Prescription prescription) {
        Encounter encounter = encounterRepository.findById(prescription.getEncounterId()).orElse(null);
        if (encounter == null) {
            return null;
        }
        Appointment appointment = appointmentRepository.findById(encounter.getAppointmentId()).orElse(null);
        return appointment != null ? appointment.getPatientId() : null;
    }

    private List<String> findAllergyConflicts(UUID patientId, UUID tenantId, Medication medication) {
        String medicationName = medication.getName().toLowerCase();
        List<String> conflicts = new ArrayList<>();
        for (Allergy allergy : allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId)) {
            if (!"active".equals(allergy.getStatus())) {
                continue;
            }
            String allergen = allergy.getAllergen().toLowerCase();
            if (medicationName.contains(allergen) || allergen.contains(medicationName)) {
                conflicts.add("Patient has an active allergy to " + allergy.getAllergen());
            }
        }
        return conflicts;
    }

    private List<String> findInteractionConflicts(UUID patientId, UUID tenantId, UUID prescriptionId, Medication medication) {
        List<DrugInteractionPair> pairs = drugInteractionPairRepository.findAllByTenantId(tenantId).stream()
                .filter(pair -> pair.getMedicationAId().equals(medication.getId()) || pair.getMedicationBId().equals(medication.getId()))
                .toList();
        if (pairs.isEmpty()) {
            return List.of();
        }

        List<String> otherActiveMedicationNames = prescriptionRepository
                .findActiveMedicationNamesForPatient(patientId, tenantId, prescriptionId).stream()
                .map(String::toLowerCase)
                .toList();
        if (otherActiveMedicationNames.isEmpty()) {
            return List.of();
        }

        List<String> conflicts = new ArrayList<>();
        for (DrugInteractionPair pair : pairs) {
            UUID otherMedicationId = pair.getMedicationAId().equals(medication.getId()) ? pair.getMedicationBId() : pair.getMedicationAId();
            medicationRepository.findByIdAndTenantId(otherMedicationId, tenantId).ifPresent(otherMedication -> {
                String otherName = otherMedication.getName().toLowerCase();
                boolean patientIsOnIt = otherActiveMedicationNames.stream().anyMatch(name -> name.contains(otherName));
                if (patientIsOnIt) {
                    conflicts.add("Potential interaction with " + otherMedication.getName()
                            + (pair.getDescription() != null ? " (" + pair.getDescription() + ")" : ""));
                }
            });
        }
        return conflicts;
    }
}
