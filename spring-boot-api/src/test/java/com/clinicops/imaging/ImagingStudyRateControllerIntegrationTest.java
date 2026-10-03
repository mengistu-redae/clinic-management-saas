package com.clinicops.imaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImagingStudyRateControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListUpdateAndDeleteARate() throws Exception {
        Clinic clinic = createClinic("imaging-rate-" + UUID.randomUUID(), "Rate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/clinic/imaging-study-rates").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingStudyRateRequest("MRI-BRAIN", "MRI Brain", "mri", new BigDecimal("450.00")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/imaging-study-rates").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/clinic/imaging-study-rates/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateImagingStudyRateRequest(null, null, new BigDecimal("500.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCharge").value(500.00));

        mockMvc.perform(post("/api/clinic/imaging-study-rates/" + id + "/delete").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clinic/imaging-study-rates").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void duplicateStudyCodeConflictsAndInvalidModalityIsRejected() throws Exception {
        Clinic clinic = createClinic("imaging-rate-dup-" + UUID.randomUUID(), "Dup Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        createImagingStudyRate(clinic.getId(), "CT-ABD", "CT Abdomen", "ct", "300.00");

        mockMvc.perform(post("/api/clinic/imaging-study-rates").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingStudyRateRequest("CT-ABD", "CT Abdomen Again", "ct", new BigDecimal("300.00")))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/clinic/imaging-study-rates").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImagingStudyRateRequest("BAD", "Bad Modality", "xray-ish", new BigDecimal("1.00")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyClinicAdminCanReachThisController() throws Exception {
        Clinic clinic = createClinic("imaging-rate-roles-" + UUID.randomUUID(), "Rate Roles Clinic");
        mockMvc.perform(get("/api/clinic/imaging-study-rates").with(asProvider("prov", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
