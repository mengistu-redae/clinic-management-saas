package com.clinicops.pharmacy;

import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispenseServiceTest {

    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final MedicationRepository medicationRepository = mock(MedicationRepository.class);
    private final StockBatchRepository stockBatchRepository = mock(StockBatchRepository.class);
    private final DispenseRecordRepository dispenseRecordRepository = mock(DispenseRecordRepository.class);
    private final DispenseService service = new DispenseService(
            prescriptionRepository, medicationRepository, stockBatchRepository, dispenseRecordRepository);

    private Prescription prescription(UUID tenantId, UUID id, Integer quantityPrescribed) {
        Prescription p = new Prescription();
        p.setId(id);
        p.setTenantId(tenantId);
        p.setQuantityDispensed(quantityPrescribed);
        return p;
    }

    private Medication medication(UUID tenantId, UUID id) {
        Medication m = new Medication();
        m.setId(id);
        m.setTenantId(tenantId);
        return m;
    }

    private StockBatch batch(UUID tenantId, UUID id, UUID medicationId, int quantityOnHand) {
        StockBatch b = new StockBatch();
        b.setId(id);
        b.setTenantId(tenantId);
        b.setMedicationId(medicationId);
        b.setQuantityOnHand(quantityOnHand);
        return b;
    }

    @Test
    void aSuccessfulDispenseDecrementsTheBatchAndSavesARecord() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription(tenantId, prescriptionId, null)));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId)));
        StockBatch stockBatch = batch(tenantId, batchId, medicationId, 30);
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(stockBatch));
        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispenseRecordRepository.save(any(DispenseRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        DispenseRecord record = service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, "handed to patient"), UUID.randomUUID());

        assertThat(record.getQuantityDispensed()).isEqualTo(10);
        assertThat(stockBatch.getQuantityOnHand()).isEqualTo(20);
        assertThat(stockBatch.getStatus()).isEqualTo("active");
    }

    @Test
    void theBatchFlipsToDepletedAtExactlyZero() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription(tenantId, prescriptionId, null)));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId)));
        StockBatch stockBatch = batch(tenantId, batchId, medicationId, 10);
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(stockBatch));
        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispenseRecordRepository.save(any(DispenseRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null), UUID.randomUUID());

        assertThat(stockBatch.getQuantityOnHand()).isEqualTo(0);
        assertThat(stockBatch.getStatus()).isEqualTo("depleted");
    }

    @Test
    void insufficientStockIsRejectedAndNeverSavesAnything() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription(tenantId, prescriptionId, null)));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId)));
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(batch(tenantId, batchId, medicationId, 5)));

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null), UUID.randomUUID()))
                .isInstanceOf(InsufficientStockException.class);

        verify(stockBatchRepository, never()).save(any());
        verify(dispenseRecordRepository, never()).save(any());
    }

    @Test
    void dispensingBeyondThePrescribedTotalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        // Prescribed 20 total, 15 already dispensed - only 5 remain.
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription(tenantId, prescriptionId, 20)));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId)));
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(batch(tenantId, batchId, medicationId, 100)));
        when(dispenseRecordRepository.sumQuantityByPrescriptionId(prescriptionId)).thenReturn(15L);

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verify(stockBatchRepository, never()).save(any());
        verify(dispenseRecordRepository, never()).save(any());
    }

    @Test
    void aBatchBelongingToADifferentMedicationIsRejected() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID otherMedicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription(tenantId, prescriptionId, null)));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId)));
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(batch(tenantId, batchId, otherMedicationId, 100)));

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anUnknownPrescriptionThrowsNoSuchElement() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId,
                new DispenseRequest(UUID.randomUUID(), UUID.randomUUID(), 1, null), UUID.randomUUID()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
