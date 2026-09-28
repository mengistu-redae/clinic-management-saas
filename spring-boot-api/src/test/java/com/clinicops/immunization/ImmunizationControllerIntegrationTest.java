package com.clinicops.immunization;

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

class ImmunizationControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListAndUpdateAnImmunization() throws Exception {
        Clinic clinic = createClinic("immunization-crud-" + UUID.randomUUID(), "Immunization CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Immunization", "Patient", "+15550011111");

        String body = mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImmunizationRequest(
                                "Influenza", LocalDate.of(2026, 1, 15), 1, "LOT123", "left deltoid"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vaccineName").value("Influenza"))
                .andExpect(jsonPath("$.doseNumber").value(1))
                .andExpect(jsonPath("$.lotNumber").value("LOT123"))
                .andExpect(jsonPath("$.site").value("left deltoid"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/immunizations").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateImmunizationRequest(2, "LOT456", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doseNumber").value(2))
                .andExpect(jsonPath("$.lotNumber").value("LOT456"))
                .andExpect(jsonPath("$.site").value("left deltoid"))
                .andExpect(jsonPath("$.vaccineName").value("Influenza"));
    }

    @Test
    void missingVaccineNameOrAdministeredAtIsRejected() throws Exception {
        Clinic clinic = createClinic("immunization-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        Patient patient = createPatient(clinic.getId(), "Invalid", "Patient", "+15550022222");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doseNumber\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allThreeStaffRolesCanWriteAndReadButPatientCannot() throws Exception {
        Clinic clinic = createClinic("immunization-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15550033333");
        CreateImmunizationRequest request = new CreateImmunizationRequest("MMR", LocalDate.now(), null, null, null);

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/immunizations").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPatientIsNotFound() throws Exception {
        Clinic clinic = createClinic("immunization-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550044444");
        Clinic otherClinic = createClinic("immunization-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateImmunizationRequest("Hep B", LocalDate.now(), null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingAnImmunizationForTheWrongPatientIdIsNotFound() throws Exception {
        Clinic clinic = createClinic("immunization-mismatch-" + UUID.randomUUID(), "Mismatch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patientA = createPatient(clinic.getId(), "A", "Patient", "+15550055555");
        Patient patientB = createPatient(clinic.getId(), "B", "Patient", "+15550066666");
        Immunization immunization = createImmunization(clinic.getId(), patientA.getId(), "Varicella");

        mockMvc.perform(post("/api/patients/" + patientB.getId() + "/immunizations/" + immunization.getId() + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateImmunizationRequest(null, null, "right arm"))))
                .andExpect(status().isNotFound());
    }
}
