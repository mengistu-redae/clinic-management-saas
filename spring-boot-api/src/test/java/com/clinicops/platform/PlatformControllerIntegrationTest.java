package com.clinicops.platform;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KeycloakOrganizationClient is mocked out - a real call would hit
 * clinicops.keycloak-admin.base-url (no live Keycloak in this test
 * context), same reasoning NoNetworkJwtDecoderConfig already applies to
 * JWT decoding.
 */
class PlatformControllerIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private KeycloakOrganizationClient keycloakOrganizationClient;

    @Test
    void createListGetAndUpdateAClinic() throws Exception {
        when(keycloakOrganizationClient.createOrganization(anyString(), anyString(), anyString()))
                .thenReturn("fake-kc-org-id");

        String orgAlias = "platform-crud-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/api/platform/clinics").with(asPlatformAdmin("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClinicRequest("New Test Clinic", orgAlias, "new-test-clinic.example"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keycloakOrgId").value(orgAlias))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/platform/clinics").with(asPlatformAdmin("admin")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/platform/clinics/" + id).with(asPlatformAdmin("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Test Clinic"));

        mockMvc.perform(post("/api/platform/clinics/" + id + "/update").with(asPlatformAdmin("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicRequest("Renamed Clinic"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Clinic"));
    }

    @Test
    void aDuplicateOrgAliasIsRejectedAndNeverCallsKeycloak() throws Exception {
        String orgAlias = "platform-dup-" + UUID.randomUUID();
        createClinic(orgAlias, "Existing Clinic");

        mockMvc.perform(post("/api/platform/clinics").with(asPlatformAdmin("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClinicRequest("Dupe Clinic", orgAlias, "dupe.example"))))
                .andExpect(status().isConflict());

        org.mockito.Mockito.verifyNoInteractions(keycloakOrganizationClient);
    }

    @Test
    void deactivateThenReactivateIsIdempotentAndRoundTrips() throws Exception {
        Clinic clinic = createClinic("platform-deact-" + UUID.randomUUID(), "Deactivate Clinic");

        mockMvc.perform(post("/api/platform/clinics/" + clinic.getId() + "/deactivate").with(asPlatformAdmin("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        // Re-calling deactivate on an already-inactive clinic is idempotent, not an error.
        mockMvc.perform(post("/api/platform/clinics/" + clinic.getId() + "/deactivate").with(asPlatformAdmin("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(post("/api/platform/clinics/" + clinic.getId() + "/reactivate").with(asPlatformAdmin("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void onlyPlatformAdminCanReachThisController() throws Exception {
        mockMvc.perform(get("/api/platform/clinics").with(asClinicAdmin("admin", "some-org")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/clinics").with(asFrontDesk("fd", "some-org")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/clinics").with(asProvider("prov", "some-org")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/clinics").with(asPatient("pat")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/clinics").with(asPlatformAdmin("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void unknownClinicIdIs404() throws Exception {
        mockMvc.perform(get("/api/platform/clinics/" + UUID.randomUUID()).with(asPlatformAdmin("admin")))
                .andExpect(status().isNotFound());
    }
}
