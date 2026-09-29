package com.clinicops.payment;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.Prescription;
import com.clinicops.inventory.StockBatch;
import com.clinicops.pharmacy.Medication;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DispensePaymentControllerIntegrationTest extends AbstractIntegrationTest {

    /** Seeds a real dispense via the actual dispense endpoint, same as this app's own established convention for getting a real DispenseRecord in tests. */
    private UUID seedDispenseRecord(Clinic clinic, String orgAlias) throws Exception {
        Provider provider = createProvider(clinic.getId(), "Dr. Bill");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        Encounter encounter = createEncounter(clinic.getId(), appointment.getId(), provider.getId());
        Prescription prescription = createPrescription(clinic.getId(), encounter.getId(), "Amoxicillin", null);
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        medication.setUnitPrice(new BigDecimal("2.50"));
        medicationRepository.save(medication);
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.DispenseRequest(medication.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test
    void recordsAndListsADispensePayment() throws Exception {
        Clinic clinic = createClinic("pay-dispense-" + UUID.randomUUID(), "Pay Dispense Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias);

        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/payments").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("25.00"), "cash", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(25.00))
                .andExpect(jsonPath("$.dispenseRecordId").value(dispenseRecordId.toString()));

        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/payments").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pharmacistCanBothReadAndRecordButProviderIsForbidden() throws Exception {
        Clinic clinic = createClinic("pay-dispense-role-" + UUID.randomUUID(), "Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias);

        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/payments").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("5.00"), "cash", null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMismatchedInvoiceIdIsRejected() throws Exception {
        Clinic clinic = createClinic("pay-dispense-mismatch-" + UUID.randomUUID(), "Mismatch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias);

        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/payments").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("25.00"), "cash", null, UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantDispensePaymentsAreNotFound() throws Exception {
        Clinic clinic = createClinic("pay-dispense-tenant-a-" + UUID.randomUUID(), "Clinic A");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias);

        Clinic otherClinic = createClinic("pay-dispense-tenant-b-" + UUID.randomUUID(), "Clinic B");
        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/payments").with(asPharmacist("pharm", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
