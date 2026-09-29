package com.clinicops.pharmacy;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.Prescription;
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

class ControlledSubstanceDispenseControllerIntegrationTest extends AbstractIntegrationTest {

    private Prescription seedPrescription(UUID tenantId, String medicationName) {
        Provider provider = createProvider(tenantId, "Dr. Seed");
        AppointmentType type = createAppointmentType(tenantId, "Visit", 30, "50.00");
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(tenantId, slot.getId(), null, provider.getId(), type.getId(), null);
        Encounter encounter = createEncounter(tenantId, appointment.getId(), provider.getId());
        return createPrescription(tenantId, encounter.getId(), medicationName, null);
    }

    private Medication controlledMedication(UUID tenantId, String name, String schedule) {
        Medication medication = createMedication(tenantId, name);
        medication.setControlledSubstanceSchedule(schedule);
        return medicationRepository.save(medication);
    }

    @Test
    void requestThenCosignByADifferentPharmacistDecrementsStockAndProducesARecord() throws Exception {
        Clinic clinic = createClinic("csd-happy-" + UUID.randomUUID(), "Controlled Substance Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedPrescription(clinic.getId(), "Oxycodone");
        Medication medication = controlledMedication(clinic.getId(), "Oxycodone", "schedule_ii");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests")
                        .with(asPharmacist("pharm1", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RequestControlledSubstanceDispenseRequest(medication.getId(), batch.getId(), 10, "for pain", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn().getResponse().getContentAsString();
        UUID pendingId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/pharmacy/controlled-substance-requests").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pendingId + "/cosign")
                        .with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cosigned"))
                .andExpect(jsonPath("$.dispenseRecordId").exists());

        StockBatch reloaded = stockBatchRepository.findByIdAndTenantId(batch.getId(), clinic.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuantityOnHand()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(dispenseRecordRepository.findAllByPrescriptionIdAndTenantId(prescription.getId(), clinic.getId()))
                .hasSize(1);
    }

    @Test
    void theSameUserCannotCosignTheirOwnRequest() throws Exception {
        Clinic clinic = createClinic("csd-selfcosign-" + UUID.randomUUID(), "Self Cosign Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedPrescription(clinic.getId(), "Fentanyl");
        Medication medication = controlledMedication(clinic.getId(), "Fentanyl", "schedule_ii");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests")
                        .with(asPharmacist("pharm1", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RequestControlledSubstanceDispenseRequest(medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID pendingId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pendingId + "/cosign")
                        .with(asPharmacist("pharm1", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectingALeavesStockUntouched() throws Exception {
        Clinic clinic = createClinic("csd-reject-" + UUID.randomUUID(), "Reject Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedPrescription(clinic.getId(), "Morphine");
        Medication medication = controlledMedication(clinic.getId(), "Morphine", "schedule_ii");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests")
                        .with(asPharmacist("pharm1", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RequestControlledSubstanceDispenseRequest(medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID pendingId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pendingId + "/reject")
                        .with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectControlledSubstanceDispenseRequest("not authorized"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("not authorized"));

        StockBatch reloaded = stockBatchRepository.findByIdAndTenantId(batch.getId(), clinic.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuantityOnHand()).isEqualTo(30);
    }

    @Test
    void directDispenseOfAControlledSubstanceIsRejected() throws Exception {
        Clinic clinic = createClinic("csd-direct-" + UUID.randomUUID(), "Direct Dispense Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedPrescription(clinic.getId(), "Codeine");
        Medication medication = controlledMedication(clinic.getId(), "Codeine", "schedule_iii");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestingAgainstANonControlledMedicationIsRejected() throws Exception {
        Clinic clinic = createClinic("csd-noncontrolled-" + UUID.randomUUID(), "Non Controlled Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedPrescription(clinic.getId(), "Amoxicillin");
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests")
                        .with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RequestControlledSubstanceDispenseRequest(medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frontDeskAndProviderCannotReachControlledSubstanceRequests() throws Exception {
        Clinic clinic = createClinic("csd-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/controlled-substance-requests").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pharmacy/controlled-substance-requests").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cosigningAnotherTenantsRequestIsNotFound() throws Exception {
        Clinic clinic = createClinic("csd-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("csd-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Prescription prescription = seedPrescription(clinic.getId(), "Diazepam");
        Medication medication = controlledMedication(clinic.getId(), "Diazepam", "schedule_iv");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests")
                        .with(asPharmacist("pharm1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RequestControlledSubstanceDispenseRequest(medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID pendingId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pendingId + "/cosign")
                        .with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
