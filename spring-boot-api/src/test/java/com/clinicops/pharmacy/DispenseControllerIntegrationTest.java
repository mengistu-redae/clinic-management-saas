package com.clinicops.pharmacy;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.Prescription;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DispenseControllerIntegrationTest extends AbstractIntegrationTest {

    /** Full patient/appointment/encounter chain a real prescription hangs off of - same fixture shape TenantIsolationIntegrationTest's vitals case already uses. */
    private Prescription seedActivePrescription(UUID tenantId, String medicationName, Integer quantityPrescribed) {
        Patient patient = createPatient(tenantId, "Jane", "Doe", "+15550001234");
        return seedActivePrescriptionForPatient(tenantId, medicationName, quantityPrescribed, patient.getId());
    }

    /** Same chain, but the patientId is explicit (or null, for a guest-channel booking) - phase 27's clinical safety checks need a real patientId to check allergies/interactions against. */
    private Prescription seedActivePrescriptionForPatient(UUID tenantId, String medicationName, Integer quantityPrescribed, UUID patientId) {
        Provider provider = createProvider(tenantId, "Dr. Seed");
        AppointmentType type = createAppointmentType(tenantId, "Visit", 30, "50.00");
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(tenantId, slot.getId(), patientId, provider.getId(), type.getId(), null);
        Encounter encounter = createEncounter(tenantId, appointment.getId(), provider.getId());
        return createPrescription(tenantId, encounter.getId(), medicationName, quantityPrescribed);
    }

    @Test
    void queueIncludesAnActivePrescriptionAwaitingDispense() throws Exception {
        Clinic clinic = createClinic("dispense-queue-" + UUID.randomUUID(), "Queue Clinic");
        Prescription prescription = seedActivePrescription(clinic.getId(), "Amoxicillin 500mg", 30);

        mockMvc.perform(get("/api/pharmacy/queue").with(asPharmacist("pharm", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(prescription.getId().toString()))
                .andExpect(jsonPath("$[0].medicationName").value("Amoxicillin 500mg"));
    }

    @Test
    void dispensingTheFullPrescribedQuantityRemovesItFromTheQueue() throws Exception {
        Clinic clinic = createClinic("dispense-full-" + UUID.randomUUID(), "Full Dispense Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedActivePrescription(clinic.getId(), "Metformin 500mg", 10);
        Medication medication = createMedication(clinic.getId(), "Metformin 500mg");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 10, "full course", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityDispensed").value(10));

        mockMvc.perform(get("/api/pharmacy/queue").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dispensingMoreThanOnHandIsRejectedWithConflict() throws Exception {
        Clinic clinic = createClinic("dispense-insufficient-" + UUID.randomUUID(), "Insufficient Stock Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedActivePrescription(clinic.getId(), "Warfarin 5mg", null);
        Medication medication = createMedication(clinic.getId(), "Warfarin 5mg");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 5);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyPharmacistAndClinicAdminCanReachTheQueueOrDispense() throws Exception {
        Clinic clinic = createClinic("dispense-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/queue").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pharmacy/queue").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRealAllergyConflictBlocksTheDispenseUnlessAcknowledged() throws Exception {
        Clinic clinic = createClinic("dispense-allergy-" + UUID.randomUUID(), "Allergy Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Jane", "Doe", "+15550002222");
        Prescription prescription = seedActivePrescriptionForPatient(clinic.getId(), "Amoxicillin", null, patient.getId());
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);
        createAllergy(clinic.getId(), patient.getId(), "Amoxicillin");

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 10, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyOverrideAcknowledged").value(true));
    }

    @Test
    void aRealInteractionConflictBlocksTheDispenseUnlessAcknowledged() throws Exception {
        Clinic clinic = createClinic("dispense-interaction-" + UUID.randomUUID(), "Interaction Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Jane", "Doe", "+15550003333");
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");
        Medication aspirin = createMedication(clinic.getId(), "Aspirin");
        createDrugInteractionPair(clinic.getId(), warfarin.getId(), aspirin.getId());
        // The patient is already on Aspirin (a separate active prescription).
        seedActivePrescriptionForPatient(clinic.getId(), "Aspirin", null, patient.getId());
        Prescription warfarinPrescription = seedActivePrescriptionForPatient(clinic.getId(), "Warfarin", null, patient.getId());
        StockBatch batch = createStockBatch(clinic.getId(), warfarin.getId(), 100);

        mockMvc.perform(post("/api/prescriptions/" + warfarinPrescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(warfarin.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/prescriptions/" + warfarinPrescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(warfarin.getId(), batch.getId(), 10, null, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyOverrideAcknowledged").value(true));
    }

    @Test
    void aGuestChannelPrescriptionDispensesNormallyWithNoSafetyCheck() throws Exception {
        Clinic clinic = createClinic("dispense-guest-" + UUID.randomUUID(), "Guest Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        // No patient - a guest-channel booking has no patientId at all.
        Prescription prescription = seedActivePrescriptionForPatient(clinic.getId(), "Amoxicillin", null, null);
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyOverrideAcknowledged").value(false));
    }
}
