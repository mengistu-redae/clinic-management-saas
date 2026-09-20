package com.clinicops.patient;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PatientController had zero dedicated test coverage before this - a real
 * gap for ongoing operational surface (front-desk walk-in registration),
 * not a one-time admin setup endpoint. Same create/list/get/search shape
 * this app's other resource-CRUD tests already use (RoomControllerIntegrationTest
 * read for the shape).
 */
class PatientControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndSearchAPatient() throws Exception {
        Clinic clinic = createClinic("patient-crud-" + UUID.randomUUID(), "Patient CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/patients").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePatientRequest("Jane", "Walkin", null, "+15550001111", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").value("Walkin"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/patients").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/patients/" + id).with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+15550001111"));

        mockMvc.perform(get("/api/patients").with(asFrontDesk("fd", orgAlias)).param("query", "walkin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/patients").with(asFrontDesk("fd", orgAlias)).param("query", "nobody-matches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void missingRequiredFieldsAreRejected() throws Exception {
        Clinic clinic = createClinic("patient-badreq-" + UUID.randomUUID(), "Bad Request Clinic");

        mockMvc.perform(post("/api/patients").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePatientRequest("", "Walkin", null, null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanRegisterAPatient() throws Exception {
        Clinic clinic = createClinic("patient-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        CreatePatientRequest request = new CreatePatientRequest("Role", "Check", null, null, null, null, null);

        mockMvc.perform(post("/api/patients").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/patients").with(asPatient("pat"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/patients").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPatientIsNotFoundAndExcludedFromListsAndSearch() throws Exception {
        Clinic clinic = createClinic("patient-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("patient-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Patient patient = createPatient(clinic.getId(), "Isolated", "Patient", "+15559998888");

        mockMvc.perform(get("/api/patients/" + patient.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/patients").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/patients").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())).param("query", "Isolated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
