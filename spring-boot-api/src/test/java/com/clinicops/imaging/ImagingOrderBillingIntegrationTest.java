package com.clinicops.imaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.payment.CreatePaymentRequest;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Imaging orders as a 4th billable owner type (2026-10-04) - mirrors the identical shape phase 31 (dispense billing) already proved for a third owner type. */
class ImagingOrderBillingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void generatesAnInvoiceRecordsAPaymentAndDownloadsAPdf() throws Exception {
        Clinic clinic = createClinic("imaging-billing-" + UUID.randomUUID(), "Billing Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Billing");
        Patient patient = createPatient(clinic.getId(), "Billing", "Patient", "+15555550000");
        ImagingOrder order = createImagingOrder(clinic.getId(), patient.getId(), provider.getId(), "completed");

        String invoiceBody = mockMvc.perform(post("/api/imaging-orders/" + order.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(75.00))
                .andReturn().getResponse().getContentAsString();
        UUID invoiceId = UUID.fromString(objectMapper.readTree(invoiceBody).get("id").asText());

        mockMvc.perform(post("/api/imaging-orders/" + order.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/imaging-orders/" + order.getId() + "/invoice/pdf").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/imaging-orders/" + order.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("75.00"), "card", null, invoiceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagingOrderId").value(order.getId().toString()))
                .andExpect(jsonPath("$.gatewayStatus").value("succeeded"));

        mockMvc.perform(get("/api/imaging-orders/" + order.getId() + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aClaimCanBeFiledAgainstAnImagingOrderInvoice() throws Exception {
        Clinic clinic = createClinic("imaging-claim-" + UUID.randomUUID(), "Claim Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Claim");
        Patient patient = createPatient(clinic.getId(), "Claim", "Patient", "+15555550001");
        ImagingOrder order = createImagingOrder(clinic.getId(), patient.getId(), provider.getId(), "completed");

        String invoiceBody = mockMvc.perform(post("/api/imaging-orders/" + order.getId() + "/invoice").with(asFrontDesk("fd", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        UUID invoiceId = UUID.fromString(objectMapper.readTree(invoiceBody).get("id").asText());

        var policy = createInsurancePolicy(clinic.getId(), patient.getId(), "Acme Health");

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.insurance.CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedAmount").value(75.00))
                .andExpect(jsonPath("$.patientId").value(patient.getId().toString()));
    }

    @Test
    void providerIsForbiddenFromGeneratingOrRecordingButCanRead() throws Exception {
        Clinic clinic = createClinic("imaging-billing-roles-" + UUID.randomUUID(), "Billing Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Roles");
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15555550002");
        ImagingOrder order = createImagingOrder(clinic.getId(), patient.getId(), provider.getId(), "completed");

        mockMvc.perform(post("/api/imaging-orders/" + order.getId() + "/invoice").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/imaging-orders/" + order.getId() + "/invoice").with(asProvider("prov", orgAlias)))
                .andExpect(status().isNotFound());
    }
}
