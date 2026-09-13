package com.clinicops.clinicsettings;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClinicSettingsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void getWithNoRowReturnsPureDefaults() throws Exception {
        Clinic clinic = createClinic("settings-defaults-" + UUID.randomUUID(), "Defaults Clinic");

        mockMvc.perform(get("/api/clinic/settings").with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.rescheduleMinNoticeHours").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.effective.rescheduleMinNoticeHours").value(4))
                .andExpect(jsonPath("$.defaults.rescheduleMinNoticeHours").value(4));
    }

    @Test
    void fullReplaceThenNullRevertsToDefault() throws Exception {
        Clinic clinic = createClinic("settings-replace-" + UUID.randomUUID(), "Replace Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/settings").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicSettingsRequest(
                                new BigDecimal("8.5"), null, null, 48, null,
                                "555-0100", "support@clinic.test", "1 Main St", "https://clinic.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.rescheduleMinNoticeHours").value(48))
                .andExpect(jsonPath("$.effective.rescheduleMinNoticeHours").value(48))
                .andExpect(jsonPath("$.effective.rescheduleFeePatientPortal").value(0.00));

        // A later full-replace with rescheduleMinNoticeHours omitted (null) reverts it to the platform default.
        mockMvc.perform(post("/api/clinic/settings").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicSettingsRequest(
                                null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.rescheduleMinNoticeHours").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.effective.rescheduleMinNoticeHours").value(4));
    }

    @Test
    void settingsAndBrandingAreDisjoint() throws Exception {
        Clinic clinic = createClinic("settings-disjoint-" + UUID.randomUUID(), "Disjoint Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/settings").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicSettingsRequest(
                                null, null, null, null, null, "555-0100", null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/clinic/branding").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicBrandingRequest(
                                "https://clinic.test/logo.png", "#112233", "#445566", "Branded Clinic", "footer"))))
                .andExpect(status().isOk());

        // Settings row still shows the earlier support phone, untouched by the branding write.
        mockMvc.perform(get("/api/clinic/settings").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.supportPhone").value("555-0100"));

        mockMvc.perform(get("/api/clinic/branding").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Branded Clinic"))
                .andExpect(jsonPath("$.logoUrl").value("https://clinic.test/logo.png"));
    }

    @Test
    void brandingDisplayNameFallsBackToClinicNameWhenUnset() throws Exception {
        Clinic clinic = createClinic("branding-fallback-" + UUID.randomUUID(), "Fallback Clinic");

        mockMvc.perform(get("/api/clinic/branding").with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Fallback Clinic"))
                .andExpect(jsonPath("$.logoUrl").value(Matchers.nullValue()));
    }

    @Test
    void brandingGetAllowsStaffReadRolesButPatchIsClinicAdminOnly() throws Exception {
        Clinic clinic = createClinic("branding-roles-" + UUID.randomUUID(), "Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/branding").with(asFrontDesk("fd", orgAlias))).andExpect(status().isOk());
        mockMvc.perform(get("/api/clinic/branding").with(asProvider("prov", orgAlias))).andExpect(status().isOk());
        mockMvc.perform(get("/api/clinic/branding").with(asPatient("pat"))).andExpect(status().isForbidden());

        mockMvc.perform(post("/api/clinic/branding").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicBrandingRequest(null, null, null, "x", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidBrandingFieldsAreRejected() throws Exception {
        Clinic clinic = createClinic("branding-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/branding").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateClinicBrandingRequest(
                                "not-a-url", "not-a-color", null, null, null))))
                .andExpect(status().isBadRequest());
    }
}
