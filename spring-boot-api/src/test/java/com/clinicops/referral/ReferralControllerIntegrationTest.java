package com.clinicops.referral;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReferralControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsAnInternalReferralListsAndUpdatesIt() throws Exception {
        Clinic clinic = createClinic("referral-internal-" + UUID.randomUUID(), "Internal Referral Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Referral", "Patient", "+15550001111");
        Provider referring = createProvider(clinic.getId(), "Dr. Referring");
        Provider receiving = createProvider(clinic.getId(), "Dr. Receiving");

        String body = mockMvc.perform(post("/api/referrals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), receiving.getId(), null, null,
                                "Cardiology", "Chest pain workup", "Patient reports intermittent chest pain", "urgent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.priority").value("urgent"))
                .andExpect(jsonPath("$.receivingProviderId").value(receiving.getId().toString()))
                .andExpect(jsonPath("$.externalProviderName").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/referrals").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/referrals/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateReferralRequest("accepted", null, "Will see next week", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.notes").value("Will see next week"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());

        mockMvc.perform(post("/api/referrals/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateReferralRequest("completed", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.completedAt").exists());
    }

    @Test
    void createsAnExternalReferral() throws Exception {
        Clinic clinic = createClinic("referral-external-" + UUID.randomUUID(), "External Referral Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "External", "Patient", "+15550002222");
        Provider referring = createProvider(clinic.getId(), "Dr. Referring");

        mockMvc.perform(post("/api/referrals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), null, "Dr. Outside", "City Specialty Clinic",
                                "Dermatology", "Suspicious mole", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalProviderName").value("Dr. Outside"))
                .andExpect(jsonPath("$.externalClinicName").value("City Specialty Clinic"))
                .andExpect(jsonPath("$.receivingProviderId").doesNotExist())
                .andExpect(jsonPath("$.priority").value("routine"));
    }

    @Test
    void requiresExactlyOneOfInternalOrExternal() throws Exception {
        Clinic clinic = createClinic("referral-neither-" + UUID.randomUUID(), "Neither Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Neither", "Patient", "+15550003333");
        Provider referring = createProvider(clinic.getId(), "Dr. Referring");
        Provider receiving = createProvider(clinic.getId(), "Dr. Receiving");

        // Neither internal nor external given.
        mockMvc.perform(post("/api/referrals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), null, null, null, null, "Reason", null, null))))
                .andExpect(status().isBadRequest());

        // Both given.
        mockMvc.perform(post("/api/referrals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), receiving.getId(), "Dr. Outside", null, null, "Reason", null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidPriorityOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("referral-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Invalid", "Patient", "+15550004444");
        Provider referring = createProvider(clinic.getId(), "Dr. Referring");
        Provider receiving = createProvider(clinic.getId(), "Dr. Receiving");

        mockMvc.perform(post("/api/referrals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), receiving.getId(), null, null, null, "Reason", null, "asap"))))
                .andExpect(status().isBadRequest());

        Referral referral = createReferral(clinic.getId(), patient.getId(), referring.getId(), receiving.getId());
        mockMvc.perform(post("/api/referrals/" + referral.getId() + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateReferralRequest("in-progress", null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyProviderAndClinicAdminCanReachThisController() throws Exception {
        Clinic clinic = createClinic("referral-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Patient patient = createPatient(clinic.getId(), "Roles", "Patient", "+15550005555");
        Provider referring = createProvider(clinic.getId(), "Dr. Referring");
        Provider receiving = createProvider(clinic.getId(), "Dr. Receiving");
        CreateReferralRequest request = new CreateReferralRequest(
                patient.getId(), null, referring.getId(), receiving.getId(), null, null, null, "Reason", null, null);

        mockMvc.perform(post("/api/referrals").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/referrals").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPatientOrProviderIsNotFound() throws Exception {
        Clinic clinic = createClinic("referral-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550006666");
        Provider referring = createProvider(clinic.getId(), "Dr. A");
        Clinic otherClinic = createClinic("referral-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Provider otherReceiving = createProvider(otherClinic.getId(), "Dr. B");

        // A patient from clinic A, called under clinic B's token.
        mockMvc.perform(post("/api/referrals").with(asProvider("prov", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReferralRequest(
                                patient.getId(), null, referring.getId(), otherReceiving.getId(), null, null, null, "Reason", null, null))))
                .andExpect(status().isNotFound());

        Referral referral = createReferral(clinic.getId(), patient.getId(), referring.getId(), referring.getId());
        mockMvc.perform(get("/api/referrals/" + referral.getId()).with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
