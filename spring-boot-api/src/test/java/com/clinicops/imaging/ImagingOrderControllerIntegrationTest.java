package com.clinicops.imaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImagingOrderControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateAnImagingOrder() throws Exception {
        Clinic clinic = createClinic("imaging-crud-" + UUID.randomUUID(), "Imaging CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Imaging");
        Provider provider2 = createProvider(clinic.getId(), "Dr. Second");
        Patient patient = createPatient(clinic.getId(), "Imaging", "Patient", "+15553330000");
        createImagingStudyRate(clinic.getId(), "CXR", "Chest X-ray, 2 views", "xray", "75.00");

        String body = mockMvc.perform(post("/api/imaging-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingOrderRequest(
                                patient.getId(), provider.getId(), "xray", "Chest X-ray, 2 views", "CXR", null, "Cough x 2 weeks"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ordered"))
                .andExpect(jsonPath("$.totalCost").value(75.00))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/imaging-orders").with(asImagingTechnologist("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/imaging-orders/" + id).with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderRef").isNotEmpty());

        mockMvc.perform(post("/api/imaging-orders/" + id + "/update").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateImagingOrderRequest(
                                provider2.getId(), null, null, null, "urgent", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("urgent"));
    }

    @Test
    void creatingWithAnUnconfiguredStudyCodeIsRejected() throws Exception {
        Clinic clinic = createClinic("imaging-norate-" + UUID.randomUUID(), "No Rate Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. NoRate");
        Patient patient = createPatient(clinic.getId(), "No", "Rate", "+15553330001");

        mockMvc.perform(post("/api/imaging-orders").with(asProvider("prov", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingOrderRequest(
                                patient.getId(), provider.getId(), "xray", "Chest X-ray", "UNKNOWN", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyProviderAndClinicAdminCanWriteButImagingTechnologistCanRead() throws Exception {
        Clinic clinic = createClinic("imaging-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Roles");
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15553330002");
        createImagingStudyRate(clinic.getId(), "CXR2", "Chest X-ray", "xray", "75.00");

        mockMvc.perform(post("/api/imaging-orders").with(asImagingTechnologist("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingOrderRequest(
                                patient.getId(), provider.getId(), "xray", "Chest X-ray", "CXR2", null, null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/imaging-orders").with(asImagingTechnologist("tech", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/imaging-orders").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantImagingOrderIsNotFound() throws Exception {
        Clinic clinic = createClinic("imaging-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Provider provider = createProvider(clinic.getId(), "Dr. A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15553330003");
        ImagingOrder order = createImagingOrder(clinic.getId(), patient.getId(), provider.getId(), "ordered");
        Clinic otherClinic = createClinic("imaging-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/imaging-orders/" + order.getId()).with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
