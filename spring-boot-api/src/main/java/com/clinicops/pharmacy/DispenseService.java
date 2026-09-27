package com.clinicops.pharmacy;

import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - same "not a contended
 * resource" reasoning EncounterService already gives for skipping the
 * Redis-lock split-bean pattern (SlotLockService/AppointmentWriter): two
 * pharmacists dispensing from the exact same batch at the exact same
 * instant is a low-risk race this app doesn't guard against anywhere else
 * at this level either.
 */
@Service
public class DispenseService {

    private final PrescriptionRepository prescriptionRepository;
    private final MedicationRepository medicationRepository;
    private final StockBatchRepository stockBatchRepository;
    private final DispenseRecordRepository dispenseRecordRepository;

    public DispenseService(
            PrescriptionRepository prescriptionRepository,
            MedicationRepository medicationRepository,
            StockBatchRepository stockBatchRepository,
            DispenseRecordRepository dispenseRecordRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.medicationRepository = medicationRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
    }

    @Transactional
    public DispenseRecord dispense(UUID prescriptionId, UUID tenantId, DispenseRequest request, UUID dispensedBy) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new NoSuchElementException("Prescription not found: " + prescriptionId));

        medicationRepository.findByIdAndTenantId(request.medicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + request.medicationId()));

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
        return dispenseRecordRepository.save(record);
    }
}
