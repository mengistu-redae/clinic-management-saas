package com.clinicops.pharmacy;

import com.clinicops.allergy.Allergy;
import com.clinicops.allergy.AllergyRepository;
import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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
    private final AllergyRepository allergyRepository = mock(AllergyRepository.class);
    private final DrugInteractionPairRepository drugInteractionPairRepository = mock(DrugInteractionPairRepository.class);
    private final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final DispenseService service = new DispenseService(
            prescriptionRepository, medicationRepository, stockBatchRepository, dispenseRecordRepository,
            allergyRepository, drugInteractionPairRepository, encounterRepository, appointmentRepository);

    private Prescription prescription(UUID tenantId, UUID id, Integer quantityPrescribed) {
        Prescription p = new Prescription();
        p.setId(id);
        p.setTenantId(tenantId);
        p.setQuantityDispensed(quantityPrescribed);
        return p;
    }

    /** Wires the full encounter/appointment chain so DispenseService can resolve a real patientId. */
    private Prescription prescriptionForPatient(UUID tenantId, UUID id, UUID patientId) {
        UUID encounterId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        Prescription p = prescription(tenantId, id, null);
        p.setEncounterId(encounterId);

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setTenantId(tenantId);
        encounter.setAppointmentId(appointmentId);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setTenantId(tenantId);
        appointment.setPatientId(patientId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        return p;
    }

    private Medication medication(UUID tenantId, UUID id) {
        Medication m = new Medication();
        m.setId(id);
        m.setTenantId(tenantId);
        return m;
    }

    private Medication medication(UUID tenantId, UUID id, String name) {
        Medication m = medication(tenantId, id);
        m.setName(name);
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
                new DispenseRequest(medicationId, batchId, 10, "handed to patient", false), UUID.randomUUID());

        assertThat(record.getQuantityDispensed()).isEqualTo(10);
        assertThat(stockBatch.getQuantityOnHand()).isEqualTo(20);
        assertThat(stockBatch.getStatus()).isEqualTo("active");
        assertThat(record.isSafetyOverrideAcknowledged()).isFalse();
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

        service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID());

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

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID()))
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

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID()))
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

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId, new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anUnknownPrescriptionThrowsNoSuchElement() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId,
                new DispenseRequest(UUID.randomUUID(), UUID.randomUUID(), 1, null, false), UUID.randomUUID()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void aGuestChannelPrescriptionSkipsClinicalSafetyChecksEntirely() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        // patientId null - a guest booking.
        Prescription prescription = prescriptionForPatient(tenantId, prescriptionId, null);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId, "Amoxicillin")));
        StockBatch stockBatch = batch(tenantId, batchId, medicationId, 30);
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(stockBatch));
        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispenseRecordRepository.save(any(DispenseRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        DispenseRecord record = service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID());

        assertThat(record.isSafetyOverrideAcknowledged()).isFalse();
        verify(allergyRepository, never()).findAllByPatientIdAndTenantId(any(), any());
        verify(drugInteractionPairRepository, never()).findAllByTenantId(any());
    }

    @Test
    void aRealAllergyMatchBlocksWithoutAcknowledgmentAndSucceedsWithIt() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Prescription prescription = prescriptionForPatient(tenantId, prescriptionId, patientId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId, "Amoxicillin")));

        Allergy allergy = new Allergy();
        allergy.setAllergen("Amoxicillin");
        allergy.setStatus("active");
        when(allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId)).thenReturn(List.of(allergy));
        when(drugInteractionPairRepository.findAllByTenantId(tenantId)).thenReturn(List.of());

        StockBatch stockBatch = batch(tenantId, batchId, medicationId, 30);
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(stockBatch));

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID()))
                .isInstanceOf(ClinicalSafetyConflictException.class)
                .hasMessageContaining("Amoxicillin");
        verify(stockBatchRepository, never()).save(any());

        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispenseRecordRepository.save(any(DispenseRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        DispenseRecord record = service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, null, true), UUID.randomUUID());

        assertThat(record.isSafetyOverrideAcknowledged()).isTrue();
    }

    @Test
    void aRealInteractionMatchBlocksWithoutAcknowledgmentAndSucceedsWithIt() {
        UUID tenantId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID medicationId = UUID.randomUUID();
        UUID otherMedicationId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        Prescription prescription = prescriptionForPatient(tenantId, prescriptionId, patientId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(medicationRepository.findByIdAndTenantId(medicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, medicationId, "Warfarin")));
        when(medicationRepository.findByIdAndTenantId(otherMedicationId, tenantId)).thenReturn(Optional.of(medication(tenantId, otherMedicationId, "Aspirin")));

        DrugInteractionPair pair = new DrugInteractionPair();
        pair.setMedicationAId(medicationId);
        pair.setMedicationBId(otherMedicationId);
        pair.setDescription("Increased bleeding risk");
        when(drugInteractionPairRepository.findAllByTenantId(tenantId)).thenReturn(List.of(pair));
        when(allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId)).thenReturn(List.of());
        when(prescriptionRepository.findActiveMedicationNamesForPatient(patientId, tenantId, prescriptionId)).thenReturn(List.of("Aspirin"));

        StockBatch stockBatch = batch(tenantId, batchId, medicationId, 30);
        when(stockBatchRepository.findByIdAndTenantId(batchId, tenantId)).thenReturn(Optional.of(stockBatch));

        assertThatThrownBy(() -> service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, null, false), UUID.randomUUID()))
                .isInstanceOf(ClinicalSafetyConflictException.class)
                .hasMessageContaining("Aspirin");
        verify(stockBatchRepository, never()).save(any());

        when(stockBatchRepository.save(any(StockBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispenseRecordRepository.save(any(DispenseRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        DispenseRecord record = service.dispense(prescriptionId, tenantId,
                new DispenseRequest(medicationId, batchId, 10, null, true), UUID.randomUUID());

        assertThat(record.isSafetyOverrideAcknowledged()).isTrue();
    }
}
