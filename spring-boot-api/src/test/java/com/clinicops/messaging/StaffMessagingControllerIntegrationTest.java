package com.clinicops.messaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffMessagingControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void staffCanSeeTheInboxAndReplyAndTheThreadShowsBothSides() throws Exception {
        Clinic clinic = createClinic("msg-staff-" + UUID.randomUUID(), "Staff Messaging Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String patientMessageJson = mockMvc.perform(post("/api/my-messages").with(asPatient("patient-staff-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePatientMessageRequest(clinic.getId(), "When are you open on Saturday?"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID patientId = UUID.fromString(objectMapper.readTree(patientMessageJson).get("patientId").asText());

        mockMvc.perform(get("/api/clinic/message-inbox").with(asProvider("provider-staff-1", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].unreadCount").value(1));

        mockMvc.perform(get("/api/patients/{patientId}/messages", patientId).with(asProvider("provider-staff-1", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].senderType").value("patient"));

        mockMvc.perform(get("/api/clinic/message-inbox").with(asProvider("provider-staff-1", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unreadCount").value(0));

        mockMvc.perform(post("/api/patients/{patientId}/messages", patientId).with(asProvider("provider-staff-1", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendStaffMessageRequest("We're open 9-1 on Saturdays."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderType").value("staff"));

        mockMvc.perform(get("/api/my-messages").param("clinicId", clinic.getId().toString()).with(asPatient("patient-staff-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].senderType").value("staff"))
                .andExpect(jsonPath("$[1].body").value("We're open 9-1 on Saturdays."));
    }

    @Test
    void onlyProviderAndClinicAdminCanAccessTheInboxNotFrontDesk() throws Exception {
        Clinic clinic = createClinic("msg-staff-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/message-inbox").with(asFrontDesk("fd-1", orgAlias)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/clinic/message-inbox").with(asClinicAdmin("admin-1", orgAlias)))
                .andExpect(status().isOk());
    }

    @Test
    void crossTenantPatientMessagingIsNotFound() throws Exception {
        Clinic clinicA = createClinic("msg-cross-a-" + UUID.randomUUID(), "Clinic A");
        Clinic clinicB = createClinic("msg-cross-b-" + UUID.randomUUID(), "Clinic B");

        String patientMessageJson = mockMvc.perform(post("/api/my-messages").with(asPatient("patient-cross-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePatientMessageRequest(clinicA.getId(), "Hello from clinic A"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID patientId = UUID.fromString(objectMapper.readTree(patientMessageJson).get("patientId").asText());

        mockMvc.perform(get("/api/patients/{patientId}/messages", patientId).with(asProvider("provider-cross-1", clinicB.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/patients/{patientId}/messages", patientId).with(asProvider("provider-cross-1", clinicB.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendStaffMessageRequest("Should not work"))))
                .andExpect(status().isNotFound());
    }
}
