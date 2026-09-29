package com.clinicops.inventory;

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

class InventoryItemControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateAnItemThenDeactivateIt() throws Exception {
        Clinic clinic = createClinic("item-crud-" + UUID.randomUUID(), "Inventory CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/inventory/items").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateInventoryItemRequest("Nitrile Gloves (Box)", "ppe", "box", new BigDecimal("8.50"), 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.category").value("ppe"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/inventory/items").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/inventory/items/" + id + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateInventoryItemRequest(null, null, null, null, null, "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(get("/api/inventory/items").with(asClinicAdmin("admin", orgAlias)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anInvalidCategoryOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("item-badcat-" + UUID.randomUUID(), "Bad Category Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/inventory/items").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateInventoryItemRequest("Mystery Item", "not_a_category", null, null, null))))
                .andExpect(status().isBadRequest());

        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze Pads");
        mockMvc.perform(post("/api/inventory/items/" + item.getId() + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateInventoryItemRequest(null, null, null, null, null, "deleted"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void receivingStockThenWritingItOffZeroesQuantityOnHand() throws Exception {
        Clinic clinic = createClinic("item-batch-" + UUID.randomUUID(), "Batch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        InventoryItem item = createInventoryItem(clinic.getId(), "Surgical Masks (Box)");

        String body = mockMvc.perform(post("/api/inventory/items/" + item.getId() + "/stock-batches").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockBatchRequest("BATCH-1", 50, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(50))
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/inventory/items/" + item.getId() + "/stock-batches/" + batchId + "/write-off")
                        .with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteOffStockBatchRequest("expired", "past expiry date"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.quantityOnHand").value(0));
    }

    @Test
    void reorderAlertsFlagsAnUnderThresholdItemAndExcludesAnAboveThresholdOne() throws Exception {
        Clinic clinic = createClinic("item-reorder-" + UUID.randomUUID(), "Reorder Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        InventoryItem low = createInventoryItem(clinic.getId(), "Low Stock Item");
        low.setReorderThreshold(20);
        inventoryItemRepository.save(low);
        createStockBatchForItem(clinic.getId(), low.getId(), 5);

        InventoryItem healthy = createInventoryItem(clinic.getId(), "Well Stocked Item");
        healthy.setReorderThreshold(5);
        inventoryItemRepository.save(healthy);
        createStockBatchForItem(clinic.getId(), healthy.getId(), 50);

        mockMvc.perform(get("/api/inventory/reorder-alerts").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ownerType").value("inventory_item"))
                .andExpect(jsonPath("$[0].name").value("Low Stock Item"))
                .andExpect(jsonPath("$[0].currentQuantity").value(5));
    }

    @Test
    void onlyClinicAdminAndFrontDeskCanReachInventory() throws Exception {
        Clinic clinic = createClinic("item-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/items").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/items").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantItemIsNotFound() throws Exception {
        Clinic clinic = createClinic("item-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("item-tenant-b-" + UUID.randomUUID(), "Clinic B");
        InventoryItem item = createInventoryItem(clinic.getId(), "Isolated Item");

        mockMvc.perform(get("/api/inventory/items/" + item.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
