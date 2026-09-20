package com.clinicops.medicalhistory;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MedicalHistoryControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void firstCallCreatesSecondCallFullyReplacesTheSameRow() throws Exception {
        Clinic clinic = createClinic("history-upsert-" + UUID.randomUUID(), "History Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "History", "Patient", "+15550001111");
        String id = patient.getId().toString();

        mockMvc.perform(post("/api/patients/" + id + "/medical-history").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertMedicalHistoryRequest(
                                "Diabetes", "Appendectomy 2015", "Metformin 500mg", "Father: heart disease", "Non-smoker"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pastConditions").value("Diabetes"))
                .andExpect(jsonPath("$.socialHistory").value("Non-smoker"));

        // Full-replace, not partial - omitting a field on the second call clears it.
        mockMvc.perform(post("/api/patients/" + id + "/medical-history").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertMedicalHistoryRequest(
                                "Diabetes, Hypertension", "Appendectomy 2015", null, "Father: heart disease", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pastConditions").value("Diabetes, Hypertension"))
                .andExpect(jsonPath("$.currentMedications").doesNotExist())
                .andExpect(jsonPath("$.socialHistory").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(
                        medicalHistoryRepository.findByPatientIdAndTenantId(patient.getId(), clinic.getId()))
                .isPresent();
    }

    @Test
    void onlyFrontDeskClinicAdminAndProviderCanReachThisController() throws Exception {
        Clinic clinic = createClinic("history-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15550002222");
        UpsertMedicalHistoryRequest request = new UpsertMedicalHistoryRequest("x", null, null, null, null);

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/medical-history").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/medical-history").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/medical-history").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getBeforeAnyHistoryRecordedIs404() throws Exception {
        Clinic clinic = createClinic("history-get404-" + UUID.randomUUID(), "Get404 Clinic");
        Patient patient = createPatient(clinic.getId(), "Get404", "Patient", "+15550003333");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantPatientIsNotFound() throws Exception {
        Clinic clinic = createClinic("history-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550004444");
        Clinic otherClinic = createClinic("history-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertMedicalHistoryRequest("x", null, null, null, null))))
                .andExpect(status().isNotFound());
    }
}
