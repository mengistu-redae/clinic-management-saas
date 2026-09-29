package com.clinicops.inventory;

import com.clinicops.clinic.Clinic;
import com.clinicops.pharmacy.Medication;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockAdjustmentControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aPositiveCorrectionAndANegativeUsageAdjustmentBothApplyAgainstAMedicationOwnedBatch() throws Exception {
        Clinic clinic = createClinic("adj-medication-" + UUID.randomUUID(), "Adjustment Medication Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 30);

        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockAdjustmentRequest(-5, "used", "given to a walk-in"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityDelta").value(-5));

        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockAdjustmentRequest(3, "correction", "recount"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityDelta").value(3));

        StockBatch reloaded = stockBatchRepository.findByIdAndTenantId(batch.getId(), clinic.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuantityOnHand()).isEqualTo(28);
    }

    @Test
    void anAdjustmentPushingQuantityNegativeIsRejected() throws Exception {
        Clinic clinic = createClinic("adj-negative-" + UUID.randomUUID(), "Negative Adjustment Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");
        StockBatch batch = createStockBatchForItem(clinic.getId(), item.getId(), 5);

        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockAdjustmentRequest(-10, "wasted", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anExactZeroAdjustmentFlipsAnInventoryItemOwnedBatchToDepleted() throws Exception {
        Clinic clinic = createClinic("adj-zero-" + UUID.randomUUID(), "Zero Adjustment Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");
        StockBatch batch = createStockBatchForItem(clinic.getId(), item.getId(), 10);

        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockAdjustmentRequest(-10, "wasted", "dropped and contaminated"))))
                .andExpect(status().isOk());

        StockBatch reloaded = stockBatchRepository.findByIdAndTenantId(batch.getId(), clinic.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getQuantityOnHand()).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo("depleted");
    }

    @Test
    void anInvalidReasonIsRejected() throws Exception {
        Clinic clinic = createClinic("adj-badreason-" + UUID.randomUUID(), "Bad Reason Clinic");
        InventoryItem item = createInventoryItem(clinic.getId(), "Gauze");
        StockBatch batch = createStockBatchForItem(clinic.getId(), item.getId(), 10);

        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockAdjustmentRequest(-1, "stolen", null))))
                .andExpect(status().isBadRequest());
    }
}
