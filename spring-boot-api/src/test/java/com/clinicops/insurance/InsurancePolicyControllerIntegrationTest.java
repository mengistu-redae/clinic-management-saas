package com.clinicops.insurance;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsurancePolicyControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListAndUpdateAPolicy() throws Exception {
        Clinic clinic = createClinic("insurance-crud-" + UUID.randomUUID(), "Insurance CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Insurance", "Patient", "+15551110000");

        String body = mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInsurancePolicyRequest(
                                "Acme Health", "MEM-001", "GRP-9", "PPO", "primary", "Insurance Patient", "self",
                                LocalDate.of(2026, 1, 1), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payerName").value("Acme Health"))
                .andExpect(jsonPath("$.rank").value("primary"))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/insurance-policies").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies/" + id + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateInsurancePolicyRequest(
                                null, null, null, null, null, null, null, "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.payerName").value("Acme Health"));
    }

    @Test
    void anInvalidRankRelationshipOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("insurance-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Invalid", "Patient", "+15551110001");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateInsurancePolicyRequest(
                                "Acme Health", "MEM-002", null, null, "tertiary", null, "self", null, null))))
                .andExpect(status().isBadRequest());

        InsurancePolicy policy = createInsurancePolicy(clinic.getId(), patient.getId(), "Acme Health");
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies/" + policy.getId() + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateInsurancePolicyRequest(
                                null, null, null, null, null, null, null, "cancelled"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanReadOrWrite() throws Exception {
        Clinic clinic = createClinic("insurance-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15551110002");
        CreateInsurancePolicyRequest request = new CreateInsurancePolicyRequest(
                "Acme Health", "MEM-003", null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/insurance-policies").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/insurance-policies").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
    }

    @Test
    void crossTenantPatientIsNotFound() throws Exception {
        Clinic clinic = createClinic("insurance-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15551110003");
        Clinic otherClinic = createClinic("insurance-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/insurance-policies").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingAPolicyForTheWrongPatientIdIsNotFound() throws Exception {
        Clinic clinic = createClinic("insurance-mismatch-" + UUID.randomUUID(), "Mismatch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patientA = createPatient(clinic.getId(), "A", "Patient", "+15551110004");
        Patient patientB = createPatient(clinic.getId(), "B", "Patient", "+15551110005");
        InsurancePolicy policy = createInsurancePolicy(clinic.getId(), patientA.getId(), "Acme Health");

        mockMvc.perform(post("/api/patients/" + patientB.getId() + "/insurance-policies/" + policy.getId() + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateInsurancePolicyRequest(
                                null, null, null, null, null, null, null, "inactive"))))
                .andExpect(status().isNotFound());
    }
}
