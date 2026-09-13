package com.clinicops.labrate;

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

class LabRateControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetUpdateAndDeleteALabRate() throws Exception {
        Clinic clinic = createClinic("labrate-crud-" + UUID.randomUUID(), "Lab Rate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/clinic/lab-rates").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabTestRateRequest("CBC", new BigDecimal("20.00"), new BigDecimal("5.00")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/lab-rates").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/clinic/lab-rates/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLabTestRateRequest(new BigDecimal("25.00"), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCharge").value(25.00));

        mockMvc.perform(post("/api/clinic/lab-rates/" + id + "/delete").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clinic/lab-rates").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDuplicateTestCodeIsRejected() throws Exception {
        Clinic clinic = createClinic("labrate-dup-" + UUID.randomUUID(), "Dup Clinic");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "0.00");

        mockMvc.perform(post("/api/clinic/lab-rates").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabTestRateRequest("CBC", new BigDecimal("30.00"), null))))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTenantLabRateIsNotFound() throws Exception {
        Clinic clinic = createClinic("labrate-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("labrate-tenant-b-" + UUID.randomUUID(), "Clinic B");
        LabTestRate rate = createLabTestRate(clinic.getId(), "CBC", "20.00", "0.00");

        mockMvc.perform(get("/api/clinic/lab-rates/" + rate.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void frontDeskCannotReadLabRates() throws Exception {
        Clinic clinic = createClinic("labrate-role-" + UUID.randomUUID(), "Role Clinic");

        mockMvc.perform(get("/api/clinic/lab-rates").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
