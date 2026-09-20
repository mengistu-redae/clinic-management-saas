package com.clinicops.consent;

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

class ConsentControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsAndListsConsentRecordsAccumulatingRatherThanReplacing() throws Exception {
        Clinic clinic = createClinic("consent-crud-" + UUID.randomUUID(), "Consent CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Consent", "Patient", "+15550001111");
        String id = patient.getId().toString();

        mockMvc.perform(post("/api/patients/" + id + "/consent-records").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateConsentRecordRequest(
                                "general_treatment", "v1", null, "Jane Witness", "en", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentType").value("general_treatment"))
                .andExpect(jsonPath("$.consentGiven").value(true))
                .andExpect(jsonPath("$.witnessName").value("Jane Witness"));

        mockMvc.perform(post("/api/patients/" + id + "/consent-records").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateConsentRecordRequest(
                                "privacy_data", "v2", true, null, null, "allow_sms_reminders"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentType").value("privacy_data"));

        // Both rows persist - a second consent record accumulates, it never replaces the first.
        mockMvc.perform(get("/api/patients/" + id + "/consent-records").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void consentGivenDefaultsToTrueButCanBeExplicitlyDeclined() throws Exception {
        Clinic clinic = createClinic("consent-decline-" + UUID.randomUUID(), "Decline Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Decline", "Patient", "+15550002222");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateConsentRecordRequest(
                                "privacy_data", "v1", false, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentGiven").value(false));
    }

    @Test
    void anInvalidConsentTypeIsRejected() throws Exception {
        Clinic clinic = createClinic("consent-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        Patient patient = createPatient(clinic.getId(), "Invalid", "Patient", "+15550003333");

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateConsentRecordRequest(
                                "procedure_specific", "v1", null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanWriteButProviderCanRead() throws Exception {
        Clinic clinic = createClinic("consent-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15550004444");
        CreateConsentRecordRequest request = new CreateConsentRecordRequest("general_treatment", "v1", null, null, null, null);

        mockMvc.perform(post("/api/patients/" + patient.getId() + "/consent-records").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/consent-records").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/consent-records").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPatientIsNotFound() throws Exception {
        Clinic clinic = createClinic("consent-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550005555");
        Clinic otherClinic = createClinic("consent-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateConsentRecordRequest("general_treatment", "v1", null, null, null, null))))
                .andExpect(status().isNotFound());
    }
}
