package com.clinicops.inventory;

import com.clinicops.clinic.Clinic;
import com.clinicops.room.Room;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createGetAndUpdateAnAssetThenTransitionItsStatus() throws Exception {
        Clinic clinic = createClinic("asset-crud-" + UUID.randomUUID(), "Asset CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/inventory/assets").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAssetRequest(
                                "Autoclave", "SN-1001", LocalDate.of(2024, 1, 15), new BigDecimal("4500.00"), LocalDate.of(2027, 1, 15), "in the sterilization room"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_service"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/inventory/assets").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/inventory/assets/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAssetRequest(null, null, null, null, null, "under_maintenance", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("under_maintenance"));

        mockMvc.perform(post("/api/inventory/assets/" + id + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAssetRequest(null, null, null, null, null, "retired", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("retired"));
    }

    @Test
    void anInvalidStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("asset-badstatus-" + UUID.randomUUID(), "Bad Status Clinic");
        Asset asset = createAsset(clinic.getId(), "Ultrasound Machine");

        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/update").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAssetRequest(null, null, null, null, null, "broken", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assigningAndClearingARoomWorksButAnotherTenantsRoomIsRejected() throws Exception {
        Clinic clinic = createClinic("asset-room-" + UUID.randomUUID(), "Asset Room Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Asset asset = createAsset(clinic.getId(), "X-Ray Machine");
        Room room = createRoom(clinic.getId(), "Radiology");

        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/assign-room").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRoomRequest(room.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedRoomId").value(room.getId().toString()));

        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/assign-room").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRoomRequest(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedRoomId").doesNotExist());

        Clinic otherClinic = createClinic("asset-room-other-" + UUID.randomUUID(), "Other Clinic");
        Room otherRoom = createRoom(otherClinic.getId(), "Other Radiology");
        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/assign-room").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRoomRequest(otherRoom.getId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void maintenanceRecordsAccumulateRatherThanReplace() throws Exception {
        Clinic clinic = createClinic("asset-maint-" + UUID.randomUUID(), "Maintenance Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Asset asset = createAsset(clinic.getId(), "Autoclave");

        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/maintenance-records").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAssetMaintenanceRecordRequest("Annual inspection", "passed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performedBy").exists());

        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/maintenance-records").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAssetMaintenanceRecordRequest("Replaced seal", null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/inventory/assets/" + asset.getId() + "/maintenance-records").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void onlyClinicAdminAndFrontDeskCanReachAssets() throws Exception {
        Clinic clinic = createClinic("asset-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/assets").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/assets").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantAssetIsNotFound() throws Exception {
        Clinic clinic = createClinic("asset-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("asset-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Asset asset = createAsset(clinic.getId(), "Isolated Asset");

        mockMvc.perform(get("/api/inventory/assets/" + asset.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
