package com.clinicops.allergy;

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

class AllergyControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListAndUpdateAnAllergy() throws Exception {
        Clinic clinic = createClinic("allergy-crud-" + UUID.randomUUID(), "Allergy CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Allergy", "Patient", "+15550001111");

        String body = mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAllergyRequest("Penicillin", "Rash", "severe", LocalDate.of(2020, 1, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergen").value("Penicillin"))
                .andExpect(jsonPath("$.severity").value("severe"))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/allergies").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAllergyRequest(null, null, "resolved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.allergen").value("Penicillin"));
    }

    @Test
    void defaultSeverityIsModerateWhenOmitted() throws Exception {
        Clinic clinic = createClinic("allergy-default-" + UUID.randomUUID(), "Default Severity Clinic");
        Patient patient = createPatient(clinic.getId(), "Default", "Patient", "+15550002222");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAllergyRequest("Peanuts", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("moderate"));
    }

    @Test
    void anInvalidSeverityOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("allergy-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Invalid", "Patient", "+15550003333");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAllergyRequest("Latex", null, "extreme", null))))
                .andExpect(status().isBadRequest());

        Allergy allergy = createAllergy(clinic.getId(), patient.getId(), "Latex");
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies/" + allergy.getId() + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAllergyRequest(null, null, "cured"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanWriteButProviderCanRead() throws Exception {
        Clinic clinic = createClinic("allergy-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15550004444");
        CreateAllergyRequest request = new CreateAllergyRequest("Shellfish", null, null, null);

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/allergies").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/allergies").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPatientIsNotFound() throws Exception {
        Clinic clinic = createClinic("allergy-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550005555");
        Clinic otherClinic = createClinic("allergy-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAllergyRequest("Dust", null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingAnAllergyForTheWrongPatientIdIsNotFound() throws Exception {
        Clinic clinic = createClinic("allergy-mismatch-" + UUID.randomUUID(), "Mismatch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patientA = createPatient(clinic.getId(), "A", "Patient", "+15550006666");
        Patient patientB = createPatient(clinic.getId(), "B", "Patient", "+15550007777");
        Allergy allergy = createAllergy(clinic.getId(), patientA.getId(), "Bee stings");

        mockMvc.perform(post("/api/patients/" + patientB.getId() + "/allergies/" + allergy.getId() + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAllergyRequest(null, null, "resolved"))))
                .andExpect(status().isNotFound());
    }
}
