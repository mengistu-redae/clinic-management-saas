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

    public DispenseService(
            PrescriptionRepository prescriptionRepository,
            MedicationRepository medicationRepository,
            StockBatchRepository stockBatchRepository,
            DispenseRecordRepository dispenseRecordRepository,
            AllergyRepository allergyRepository,
            DrugInteractionPairRepository drugInteractionPairRepository,
            EncounterRepository encounterRepository,
            AppointmentRepository appointmentRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.medicationRepository = medicationRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.allergyRepository = allergyRepository;
        this.drugInteractionPairRepository = drugInteractionPairRepository;
        this.encounterRepository = encounterRepository;
        this.appointmentRepository = appointmentRepository;
    }

    @Transactional
    public DispenseRecord dispense(UUID prescriptionId, UUID tenantId, DispenseRequest request, UUID dispensedBy) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + prescriptionId));

        Medication medication = medicationRepository.findByIdAndTenantId(request.medicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + request.medicationId()));

        boolean conflictOverridden = checkClinicalSafety(tenantId, prescription, medication, request.acknowledgeConflict());

        StockBatch batch = stockBatchRepository.findByIdAndTenantId(request.stockBatchId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + request.stockBatchId()));
        if (!batch.getMedicationId().equals(request.medicationId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This stock batch does not belong to the given medication");
        }
        if (batch.getQuantityOnHand() < request.quantity()) {
            throw new InsufficientStockException(
                    "Only " + batch.getQuantityOnHand() + " on hand in this batch, " + request.quantity() + " requested");
        }

        if (prescription.getQuantityDispensed() != null) {
            long alreadyDispensed = dispenseRecordRepository.sumQuantityByPrescriptionId(prescriptionId);
            if (alreadyDispensed + request.quantity() > prescription.getQuantityDispensed()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This would exceed the prescribed quantity (" + prescription.getQuantityDispensed()
                                + ", " + alreadyDispensed + " already dispensed)");
            }
        }

        batch.setQuantityOnHand(batch.getQuantityOnHand() - request.quantity());
        if (batch.getQuantityOnHand() == 0) {
            batch.setStatus("depleted");
        }
        stockBatchRepository.save(batch);

        DispenseRecord record = new DispenseRecord();
        record.setTenantId(tenantId);
        record.setPrescriptionId(prescriptionId);
        record.setMedicationId(request.medicationId());
        record.setStockBatchId(request.stockBatchId());
        record.setQuantityDispensed(request.quantity());
        record.setDispensedBy(dispensedBy);
        record.setNotes(request.notes());
        record.setSafetyOverrideAcknowledged(conflictOverridden);
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
