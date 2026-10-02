package com.clinicops.laborder;

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

class QcRunControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void recordingAQcRunComputesPassOrFailFromTheObservedValue() throws Exception {
        Clinic clinic = createClinic("qc-pass-" + UUID.randomUUID(), "QC Pass Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/lab-qc-runs").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true));

        mockMvc.perform(post("/api/lab-qc-runs").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "150"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false));

        mockMvc.perform(get("/api/lab-qc-runs").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/lab-qc-runs?instrumentIdentifier=ANALYZER-1").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/lab-qc-runs?instrumentIdentifier=ANALYZER-2").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aNonNumericObservedValueOrAnInvertedRangeIsRejected() throws Exception {
        Clinic clinic = createClinic("qc-invalid-" + UUID.randomUUID(), "QC Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/lab-qc-runs").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "not-a-number"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/lab-qc-runs").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(110), BigDecimal.valueOf(90), "100"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyLabTechnicianAndClinicAdminCanReadOrWriteQcRuns() throws Exception {
        Clinic clinic = createClinic("qc-role-" + UUID.randomUUID(), "QC Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/lab-qc-runs").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "100"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/lab-qc-runs").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/lab-qc-runs").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "100"))))
                .andExpect(status().isOk());
    }
}
