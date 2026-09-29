package com.clinicops.invoice;

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

class DispenseInvoiceControllerIntegrationTest extends AbstractIntegrationTest {

    private UUID seedDispenseRecord(Clinic clinic, String orgAlias, String unitPrice, int quantity) throws Exception {
        Provider provider = createProvider(clinic.getId(), "Dr. Bill");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        Encounter encounter = createEncounter(clinic.getId(), appointment.getId(), provider.getId());
        Prescription prescription = createPrescription(clinic.getId(), encounter.getId(), "Amoxicillin", null);
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        medication.setUnitPrice(new BigDecimal(unitPrice));
        medicationRepository.save(medication);
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.DispenseRequest(medication.getId(), batch.getId(), quantity, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test
    void generatesAnInvoiceFromUnitPriceTimesQuantityDispensed() throws Exception {
        Clinic clinic = createClinic("invoice-dispense-" + UUID.randomUUID(), "Invoice Dispense Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias, "2.50", 10);

        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(25.00))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.appointmentId").doesNotExist())
                .andExpect(jsonPath("$.labOrderId").doesNotExist());

        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(25.00));
    }

    @Test
    void generatingASecondInvoiceForTheSameDispenseRecordConflicts() throws Exception {
        Clinic clinic = createClinic("invoice-dispense-dup-" + UUID.randomUUID(), "Dup Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias, "1.00", 5);

        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void readingBeforeGenerationIs404() throws Exception {
        Clinic clinic = createClinic("invoice-dispense-none-" + UUID.randomUUID(), "None Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias, "1.00", 5);

        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invoicePdfRendersARealPdf() throws Exception {
        Clinic clinic = createClinic("invoice-dispense-pdf-" + UUID.randomUUID(), "Pdf Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias, "3.00", 4);

        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk());

        byte[] pdf = mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/invoice/pdf").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        org.assertj.core.api.Assertions.assertThat(pdf).isNotEmpty();
        byte[] magic = java.util.Arrays.copyOf(pdf, 4);
        org.assertj.core.api.Assertions.assertThat(magic).isEqualTo(new byte[] {'%', 'P', 'D', 'F'});
    }

    @Test
    void crossTenantDispenseRecordIs404() throws Exception {
        Clinic clinic = createClinic("invoice-dispense-tenant-a-" + UUID.randomUUID(), "Clinic A");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID dispenseRecordId = seedDispenseRecord(clinic, orgAlias, "1.00", 5);

        Clinic otherClinic = createClinic("invoice-dispense-tenant-b-" + UUID.randomUUID(), "Clinic B");
        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asPharmacist("pharm", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
