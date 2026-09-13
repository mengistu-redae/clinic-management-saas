package com.clinicops.provider;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderWorkingHoursIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListAndRemoveAWindow() throws Exception {
        Clinic clinic = createClinic("hours-crud-" + UUID.randomUUID(), "Hours CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Hours");

        String body = mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/providers/" + provider.getId() + "/working-hours").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours/" + id + "/remove").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/providers/" + provider.getId() + "/working-hours").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anOverlappingWindowIsRejected() throws Exception {
        Clinic clinic = createClinic("hours-overlap-" + UUID.randomUUID(), "Hours Overlap Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Overlap");
        createWorkingHours(clinic.getId(), provider.getId(), 1, LocalTime.of(9, 0), LocalTime.of(17, 0));

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWorkingHoursRequest(1, LocalTime.of(12, 0), LocalTime.of(18, 0)))))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonOverlappingWindowOnTheSameDayIsAccepted() throws Exception {
        Clinic clinic = createClinic("hours-nonoverlap-" + UUID.randomUUID(), "Hours Non-overlap Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. NonOverlap");
        createWorkingHours(clinic.getId(), provider.getId(), 1, LocalTime.of(9, 0), LocalTime.of(12, 0));

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWorkingHoursRequest(1, LocalTime.of(13, 0), LocalTime.of(17, 0)))))
                .andExpect(status().isOk());
    }

    @Test
    void startAfterEndIsRejected() throws Exception {
        Clinic clinic = createClinic("hours-invalid-" + UUID.randomUUID(), "Hours Invalid Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Invalid");

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateWorkingHoursRequest(1, LocalTime.of(17, 0), LocalTime.of(9, 0)))))
                .andExpect(status().isBadRequest());
    }
}
