package com.clinicops.feepolicy;

import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FeePolicyControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetUpdateAndDeleteAFeePolicy() throws Exception {
        Clinic clinic = createClinic("fee-crud-" + UUID.randomUUID(), "Fee CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/fee-policies").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateFeePolicyRequest(null, 24, 0))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/fee-policies").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/fee-policies/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateFeePolicyRequest(null, 25))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feePercent").value(25));

        mockMvc.perform(post("/api/fee-policies/" + id + "/delete").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/fee-policies").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDuplicateCutoffForTheSameProviderDefaultIsRejected() throws Exception {
        Clinic clinic = createClinic("fee-dup-" + UUID.randomUUID(), "Fee Dup Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        createFeePolicy(clinic.getId(), null, 24, 0);

        mockMvc.perform(post("/api/fee-policies").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateFeePolicyRequest(null, 24, 50))))
                .andExpect(status().isConflict());
    }

    @Test
    void aProviderFromAnotherClinicIsRejected() throws Exception {
        Clinic clinic = createClinic("fee-provider-mismatch-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("fee-provider-other-" + UUID.randomUUID(), "Clinic B");
        Provider otherProvider = createProvider(otherClinic.getId(), "Dr. Other");

        mockMvc.perform(post("/api/fee-policies").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateFeePolicyRequest(otherProvider.getId(), 24, 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frontDeskCannotReadFeePolicies() throws Exception {
        Clinic clinic = createClinic("fee-role-" + UUID.randomUUID(), "Fee Role Clinic");

        mockMvc.perform(get("/api/fee-policies").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantFeePolicyIsNotFound() throws Exception {
        Clinic clinic = createClinic("fee-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("fee-tenant-b-" + UUID.randomUUID(), "Clinic B");
        FeePolicy policy = createFeePolicy(clinic.getId(), null, 24, 0);

        mockMvc.perform(get("/api/fee-policies/" + policy.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
