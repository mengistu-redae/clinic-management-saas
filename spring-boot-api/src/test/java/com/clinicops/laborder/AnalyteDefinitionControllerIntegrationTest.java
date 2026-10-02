package com.clinicops.laborder;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyteDefinitionControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void crudRoundTripAndDuplicateIsRejected() throws Exception {
        Clinic clinic = createClinic("analyte-crud-" + UUID.randomUUID(), "Analyte CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/clinic/analyte-definitions").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, "x10^9/L", java.math.BigDecimal.valueOf(4.0), java.math.BigDecimal.valueOf(11.0), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyteName").value("WBC"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/analyte-definitions?testCode=CBC").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Duplicate testCode+analyteName rejected.
        mockMvc.perform(post("/api/clinic/analyte-definitions").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, "x10^9/L", java.math.BigDecimal.valueOf(4.0), java.math.BigDecimal.valueOf(11.0), null))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/clinic/analyte-definitions/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateAnalyteDefinitionRequest(null, "10^9/L", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("10^9/L"));

        mockMvc.perform(post("/api/clinic/analyte-definitions/" + id + "/delete").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clinic/analyte-definitions?testCode=CBC").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void readIsWidenedToLabTechnicianButWriteStaysClinicAdminOnly() throws Exception {
        Clinic clinic = createClinic("analyte-role-" + UUID.randomUUID(), "Analyte Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/analyte-definitions").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/clinic/analyte-definitions").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, null, null, null, null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/clinic/analyte-definitions").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantDefinitionIsNotFound() throws Exception {
        Clinic a = createClinic("analyte-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic b = createClinic("analyte-tenant-b-" + UUID.randomUUID(), "Clinic B");

        String body = mockMvc.perform(post("/api/clinic/analyte-definitions").with(asClinicAdmin("admin", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, null, null, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/analyte-definitions/" + id).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
