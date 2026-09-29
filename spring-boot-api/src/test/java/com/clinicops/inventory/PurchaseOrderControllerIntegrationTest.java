package com.clinicops.inventory;

import com.clinicops.clinic.Clinic;
import com.clinicops.pharmacy.Medication;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PurchaseOrderControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void creatingAndReceivingAMixedOrderAutoCreatesTheRightStockBatches() throws Exception {
        Clinic clinic = createClinic("po-mixed-" + UUID.randomUUID(), "Mixed PO Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Supplier supplier = createSupplier(clinic.getId(), "MedSupply Co");
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        InventoryItem item = createInventoryItem(clinic.getId(), "Nitrile Gloves");

        String body = mockMvc.perform(post("/api/inventory/purchase-orders").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePurchaseOrderRequest(
                                supplier.getId(), "restock", List.of(
                                new CreatePurchaseOrderRequest.PurchaseOrderLineRequest(medication.getId(), null, 100, new java.math.BigDecimal("0.10")),
                                new CreatePurchaseOrderRequest.PurchaseOrderLineRequest(null, item.getId(), 20, new java.math.BigDecimal("5.00")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("ordered"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());

        mockMvc.perform(post("/api/inventory/purchase-orders/" + orderId + "/receive").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("received"));

        List<StockBatch> medicationBatches = stockBatchRepository.findAllByMedicationIdAndTenantId(medication.getId(), clinic.getId());
        List<StockBatch> itemBatches = stockBatchRepository.findAllByInventoryItemIdAndTenantId(item.getId(), clinic.getId());
        org.assertj.core.api.Assertions.assertThat(medicationBatches).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(medicationBatches.get(0).getQuantityOnHand()).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(itemBatches).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(itemBatches.get(0).getQuantityOnHand()).isEqualTo(20);
    }

    @Test
    void receivingTwiceIsRejected() throws Exception {
        Clinic clinic = createClinic("po-receive-twice-" + UUID.randomUUID(), "Receive Twice Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Supplier supplier = createSupplier(clinic.getId(), "Supplier");
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");
        PurchaseOrder order = createPurchaseOrder(clinic.getId(), supplier.getId());
        createPurchaseOrderLine(clinic.getId(), order.getId(), null, item.getId(), 10);

        mockMvc.perform(post("/api/inventory/purchase-orders/" + order.getId() + "/receive").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inventory/purchase-orders/" + order.getId() + "/receive").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingFromOrderedWorksButCancellingAnAlreadyReceivedOrderIsRejected() throws Exception {
        Clinic clinic = createClinic("po-cancel-" + UUID.randomUUID(), "Cancel Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Supplier supplier = createSupplier(clinic.getId(), "Supplier");
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");

        PurchaseOrder cancellable = createPurchaseOrder(clinic.getId(), supplier.getId());
        createPurchaseOrderLine(clinic.getId(), cancellable.getId(), null, item.getId(), 10);
        mockMvc.perform(post("/api/inventory/purchase-orders/" + cancellable.getId() + "/cancel").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("cancelled"));

        PurchaseOrder received = createPurchaseOrder(clinic.getId(), supplier.getId());
        createPurchaseOrderLine(clinic.getId(), received.getId(), null, item.getId(), 10);
        mockMvc.perform(post("/api/inventory/purchase-orders/" + received.getId() + "/receive").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/inventory/purchase-orders/" + received.getId() + "/cancel").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void aLineWithBothOrNeitherOwnerIsRejected() throws Exception {
        Clinic clinic = createClinic("po-badline-" + UUID.randomUUID(), "Bad Line Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Supplier supplier = createSupplier(clinic.getId(), "Supplier");
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");

        mockMvc.perform(post("/api/inventory/purchase-orders").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePurchaseOrderRequest(
                                supplier.getId(), null, List.of(
                                new CreatePurchaseOrderRequest.PurchaseOrderLineRequest(medication.getId(), item.getId(), 5, null))))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/inventory/purchase-orders").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePurchaseOrderRequest(
                                supplier.getId(), null, List.of(
                                new CreatePurchaseOrderRequest.PurchaseOrderLineRequest(null, null, 5, null))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantPurchaseOrderIsNotFound() throws Exception {
        Clinic clinic = createClinic("po-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("po-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Supplier supplier = createSupplier(clinic.getId(), "Supplier");
        PurchaseOrder order = createPurchaseOrder(clinic.getId(), supplier.getId());

        mockMvc.perform(get("/api/inventory/purchase-orders/" + order.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
