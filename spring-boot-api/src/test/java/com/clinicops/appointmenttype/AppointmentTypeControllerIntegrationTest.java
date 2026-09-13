package com.clinicops.appointmenttype;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppointmentTypeControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateAnAppointmentType() throws Exception {
        Clinic clinic = createClinic("type-crud-" + UUID.randomUUID(), "Type CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/appointment-types").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentTypeRequest("Visit", 30, new BigDecimal("50.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/appointment-types/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAppointmentTypeRequest(null, 45, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(45));

        mockMvc.perform(post("/api/appointment-types/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAppointmentTypeRequest(null, -5, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicListingReturnsOnlyActiveTypesWithNoAuth() throws Exception {
        Clinic clinic = createClinic("type-public-" + UUID.randomUUID(), "Public Clinic");
        AppointmentType activeType = createAppointmentType(clinic.getId(), "Active Visit", 30, "40.00");
        AppointmentType inactiveType = createAppointmentType(clinic.getId(), "Retired Visit", 30, "40.00");
        inactiveType.setStatus("inactive");
        appointmentTypeRepository.save(inactiveType);

        mockMvc.perform(get("/api/clinics/" + clinic.getId() + "/appointment-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(activeType.getId().toString()));
    }

    @Test
    void crossTenantAppointmentTypeIsNotFound() throws Exception {
        Clinic clinic = createClinic("type-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("type-tenant-b-" + UUID.randomUUID(), "Clinic B");
        AppointmentType type = createAppointmentType(clinic.getId(), "Isolated Type", 30, "40.00");

        mockMvc.perform(get("/api/appointment-types/" + type.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
