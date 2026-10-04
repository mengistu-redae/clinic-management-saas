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

class PatientMessagingControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void sendingAMessageAutoProvisionsAPatientRowAndListingShowsThemInOrder() throws Exception {
        Clinic clinic = createClinic("msg-patient-" + UUID.randomUUID(), "Messaging Clinic");

        mockMvc.perform(post("/api/my-messages").with(asPatient("patient-msg-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePatientMessageRequest(clinic.getId(), "I have a question about my results"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderType").value("patient"))
                .andExpect(jsonPath("$.body").value("I have a question about my results"));

        mockMvc.perform(post("/api/my-messages").with(asPatient("patient-msg-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePatientMessageRequest(clinic.getId(), "Also, can I reschedule?"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my-messages").param("clinicId", clinic.getId().toString()).with(asPatient("patient-msg-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].body").value("I have a question about my results"))
                .andExpect(jsonPath("$[1].body").value("Also, can I reschedule?"));
    }

    @Test
    void messagesAtDifferentClinicsStayFullySeparateForTheSamePortalLogin() throws Exception {
        Clinic clinicA = createClinic("msg-multi-a-" + UUID.randomUUID(), "Clinic A");
        Clinic clinicB = createClinic("msg-multi-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(post("/api/my-messages").with(asPatient("patient-multi"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePatientMessageRequest(clinicA.getId(), "Message at clinic A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my-messages").param("clinicId", clinicB.getId().toString()).with(asPatient("patient-multi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void onlyAPatientTokenCanReachTheseEndpoints() throws Exception {
        Clinic clinic = createClinic("msg-roles-" + UUID.randomUUID(), "Roles Clinic");
        mockMvc.perform(get("/api/my-messages").param("clinicId", clinic.getId().toString()).with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
