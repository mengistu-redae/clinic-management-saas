package com.clinicops.room;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndDeactivateThenReactivateARoom() throws Exception {
        Clinic clinic = createClinic("room-crud-" + UUID.randomUUID(), "Room CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/rooms").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRoomRequest("Room 1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/rooms").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/rooms/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateRoomRequest(null, "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(get("/api/rooms").with(asClinicAdmin("admin", orgAlias)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/rooms/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateRoomRequest(null, "active"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void anInvalidStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("room-badstatus-" + UUID.randomUUID(), "Bad Status Clinic");
        Room room = createRoom(clinic.getId(), "Room X");

        mockMvc.perform(post("/api/rooms/" + room.getId() + "/update").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateRoomRequest(null, "closed"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantRoomIsNotFound() throws Exception {
        Clinic clinic = createClinic("room-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("room-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Room room = createRoom(clinic.getId(), "Isolated Room");

        mockMvc.perform(get("/api/rooms/" + room.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
