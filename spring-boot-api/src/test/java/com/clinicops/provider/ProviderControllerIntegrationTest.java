package com.clinicops.provider;

import com.clinicops.clinic.Clinic;
import com.clinicops.room.Room;
import com.clinicops.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateAProvider() throws Exception {
        Clinic clinic = createClinic("prov-crud-" + UUID.randomUUID(), "Provider CRUD Clinic");
        Room room = createRoom(clinic.getId(), "Room 1");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/providers").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProviderRequest(
                                "Dr. New", "Cardiology", room.getId(), "LIC-123", java.time.LocalDate.of(2030, 1, 1), "full_time"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dr. New"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.licenseNumber").value("LIC-123"))
                .andExpect(jsonPath("$.employmentStatus").value("full_time"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/providers").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/providers/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProviderRequest("Dr. Updated", null, null, "inactive", null, null, "locum"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dr. Updated"))
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.employmentStatus").value("locum"));

        // Reactivate.
        mockMvc.perform(post("/api/providers/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProviderRequest(null, null, null, "active", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void aRoomFromAnotherClinicIsRejected() throws Exception {
        Clinic clinic = createClinic("prov-room-mismatch-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("prov-room-other-" + UUID.randomUUID(), "Clinic B");
        Room otherRoom = createRoom(otherClinic.getId(), "Other Room");

        mockMvc.perform(post("/api/providers").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateProviderRequest("Dr. X", null, otherRoom.getId(), null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("prov-badstatus-" + UUID.randomUUID(), "Bad Status Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Status");

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/update").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProviderRequest(null, null, null, "on-vacation", null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantProviderIsNotFound() throws Exception {
        Clinic clinic = createClinic("prov-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("prov-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Provider provider = createProvider(clinic.getId(), "Dr. Isolated");

        mockMvc.perform(get("/api/providers/" + provider.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void linkAndUnlinkLogin() throws Exception {
        Clinic clinic = createClinic("prov-link-" + UUID.randomUUID(), "Link Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Linkable");

        // Provisions the AppUser row via a real request as that subject first (mirrors what a real first login would do) -
        // GET /api/my-schedule resolves the JWT to an AppUser before its own "no linked provider" 404, same as CurrentProviderService always does.
        mockMvc.perform(get("/api/my-schedule").with(asProvider("to-link", orgAlias))).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/link-login").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LinkProviderLoginRequest("to-link@example.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appUserId").exists());

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/unlink-login").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appUserId").value(Matchers.nullValue()));
    }

    @Test
    void linkingAnEmailThatHasNeverLoggedInIs404() throws Exception {
        Clinic clinic = createClinic("prov-link-404-" + UUID.randomUUID(), "Link 404 Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Unlinkable");

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/link-login").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LinkProviderLoginRequest("nobody@example.test"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidEmploymentStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("prov-badstatus-emp-" + UUID.randomUUID(), "Bad Employment Status Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Employment");

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/update").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProviderRequest(null, null, null, null, null, null, "on-vacation"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signatureUploadGetAndRemove() throws Exception {
        Clinic clinic = createClinic("prov-sig-" + UUID.randomUUID(), "Signature Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Signature");
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile("file", "signature.png", "image/png", pngBytes);

        mockMvc.perform(multipart("/api/providers/" + provider.getId() + "/signature").file(file)
                        .with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(provider.getId().toString()));

        byte[] downloaded = mockMvc.perform(get("/api/providers/" + provider.getId() + "/signature").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(pngBytes);

        mockMvc.perform(post("/api/providers/" + provider.getId() + "/signature/remove").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/providers/" + provider.getId() + "/signature").with(asProvider("prov", orgAlias)))
                .andExpect(status().isNotFound());

        // Idempotent re-call on a provider with no signature.
        mockMvc.perform(post("/api/providers/" + provider.getId() + "/signature/remove").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
    }

    @Test
    void anInvalidSignatureContentTypeIsRejected() throws Exception {
        Clinic clinic = createClinic("prov-sig-badtype-" + UUID.randomUUID(), "Bad Signature Type Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. BadSig");
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/providers/" + provider.getId() + "/signature").file(file)
                        .with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSignatureBeforeAnyUploadIs404() throws Exception {
        Clinic clinic = createClinic("prov-sig-404-" + UUID.randomUUID(), "Sig 404 Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. NoSig");

        mockMvc.perform(get("/api/providers/" + provider.getId() + "/signature").with(asProvider("prov", clinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyClinicAdminCanUploadOrRemoveASignature() throws Exception {
        Clinic clinic = createClinic("prov-sig-roles-" + UUID.randomUUID(), "Sig Roles Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. SigRoles");
        MockMultipartFile file = new MockMultipartFile("file", "signature.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/providers/" + provider.getId() + "/signature").file(file)
                        .with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantSignatureAccessIsNotFound() throws Exception {
        Clinic clinic = createClinic("prov-sig-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Provider provider = createProvider(clinic.getId(), "Dr. TenantSig");
        Clinic otherClinic = createClinic("prov-sig-tenant-b-" + UUID.randomUUID(), "Clinic B");
        MockMultipartFile file = new MockMultipartFile("file", "signature.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(get("/api/providers/" + provider.getId() + "/signature").with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/providers/" + provider.getId() + "/signature").file(file)
                        .with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
