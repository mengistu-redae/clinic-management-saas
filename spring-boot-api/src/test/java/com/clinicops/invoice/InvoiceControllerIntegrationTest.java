package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.clinicsettings.ClinicSettings;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvoiceControllerIntegrationTest extends AbstractIntegrationTest {

    private Appointment seedAppointment(UUID tenantId, String priceAmount) {
        Provider provider = createProvider(tenantId, "Dr. Bill");
        Patient patient = createPatient(tenantId, "Invoice", "Patient", "+15550009999");
        AppointmentType type = createAppointmentType(tenantId, "Consult", 30, priceAmount);
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(),
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(1, ChronoUnit.DAYS).plusSeconds(1800));
        return createBookedAppointment(tenantId, slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
    }

    @Test
    void generatesAnInvoiceUntaxedByDefault() throws Exception {
        Clinic clinic = createClinic("invoice-default-" + UUID.randomUUID(), "Invoice Default Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "100.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(100.00))
                .andExpect(jsonPath("$.taxAmount").value(0.00))
                .andExpect(jsonPath("$.totalAmount").value(100.00));

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/invoice").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(100.00));
    }

    @Test
    void appliesTheClinicsOverriddenTaxRate() throws Exception {
        Clinic clinic = createClinic("invoice-taxed-" + UUID.randomUUID(), "Invoice Taxed Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        ClinicSettings settings = new ClinicSettings();
        settings.setTenantId(clinic.getId());
        settings.setTaxRatePercent(new BigDecimal("8.25"));
        clinicSettingsRepository.save(settings);
        Appointment appointment = seedAppointment(clinic.getId(), "100.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(100.00))
                .andExpect(jsonPath("$.taxAmount").value(8.25))
                .andExpect(jsonPath("$.totalAmount").value(108.25));
    }

    @Test
    void generatingASecondInvoiceForTheSameAppointmentConflicts() throws Exception {
        Clinic clinic = createClinic("invoice-dup-" + UUID.randomUUID(), "Invoice Dup Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "50.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void readingBeforeGenerationIs404() throws Exception {
        Clinic clinic = createClinic("invoice-none-" + UUID.randomUUID(), "Invoice None Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "50.00");

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isNotFound());
    }

    @Test
    void providerCanReadButNotGenerate() throws Exception {
        Clinic clinic = createClinic("invoice-roles-" + UUID.randomUUID(), "Invoice Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "50.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void generatesAnInvoiceForALabOrderFromItsSnapshottedTotalCost() throws Exception {
        Clinic clinic = createClinic("invoice-lab-" + UUID.randomUUID(), "Invoice Lab Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Lab Bill");
        Patient patient = createPatient(clinic.getId(), "Lab", "Invoice", "+15550008888");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "5.00");

        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.laborder.CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new com.clinicops.laborder.TestItem("CBC", "Complete Blood Count", "blood", null)),
                                false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());

        mockMvc.perform(post("/api/lab-orders/" + orderId + "/invoice").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotalAmount").value(25.00))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                .andExpect(jsonPath("$.appointmentId").doesNotExist());
    }

    @Test
    void invoicePdfRendersARealPdfForAGeneratedInvoice() throws Exception {
        Clinic clinic = createClinic("invoice-pdf-" + UUID.randomUUID(), "Invoice Pdf Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "75.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk());

        byte[] pdf = mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/invoice/pdf").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        assertThatPdf(pdf);
    }

    @Test
    void invoicePdfBeforeGenerationIs404() throws Exception {
        Clinic clinic = createClinic("invoice-pdf-none-" + UUID.randomUUID(), "Invoice Pdf None Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedAppointment(clinic.getId(), "75.00");

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/invoice/pdf").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isNotFound());
    }

    private void assertThatPdf(byte[] bytes) {
        org.assertj.core.api.Assertions.assertThat(bytes).isNotEmpty();
        byte[] magic = java.util.Arrays.copyOf(bytes, 4);
        org.assertj.core.api.Assertions.assertThat(magic).isEqualTo(new byte[] {'%', 'P', 'D', 'F'});
    }

    @Test
    void labOrderInvoicePdfRendersARealPdf() throws Exception {
        Clinic clinic = createClinic("invoice-pdf-lab-" + UUID.randomUUID(), "Invoice Pdf Lab Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Lab Pdf");
        Patient patient = createPatient(clinic.getId(), "Lab", "PdfPatient", "+15550007777");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "5.00");

        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.laborder.CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new com.clinicops.laborder.TestItem("CBC", "Complete Blood Count", "blood", null)),
                                false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());

        mockMvc.perform(post("/api/lab-orders/" + orderId + "/invoice").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        byte[] pdf = mockMvc.perform(get("/api/lab-orders/" + orderId + "/invoice/pdf").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        assertThatPdf(pdf);
    }

    @Test
    void crossTenantAppointmentIs404() throws Exception {
        Clinic clinic = createClinic("invoice-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("invoice-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Appointment appointment = seedAppointment(clinic.getId(), "50.00");

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/invoice")
                        .with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
