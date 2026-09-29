package com.clinicops.analytics;

import com.clinicops.clinic.Clinic;
import com.clinicops.inventory.Asset;
import com.clinicops.inventory.InventoryItem;
import com.clinicops.inventory.StockBatch;
import com.clinicops.pharmacy.Medication;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryAnalyticsControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void totalValuationSumsBothOwnerTypesAndExcludesANonActiveBatch() throws Exception {
        Clinic clinic = createClinic("inv-analytics-val-" + UUID.randomUUID(), "Valuation Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        medication.setUnitPrice(new BigDecimal("2.00"));
        medicationRepository.save(medication);
        createStockBatch(clinic.getId(), medication.getId(), 50); // 50 * 2.00 = 100.00

        InventoryItem item = createInventoryItem(clinic.getId(), "Nitrile Gloves (Box)");
        item.setUnitPrice(new BigDecimal("5.00"));
        inventoryItemRepository.save(item);
        createStockBatchForItem(clinic.getId(), item.getId(), 10); // 10 * 5.00 = 50.00

        StockBatch expiredBatch = createStockBatch(clinic.getId(), medication.getId(), 1000);
        expiredBatch.setStatus("expired");
        stockBatchRepository.save(expiredBatch);

        mockMvc.perform(get("/api/inventory/analytics").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValuation").value(150.00));
    }

    @Test
    void expiringSoonIncludesABatchInsideTheWindowAndExcludesOutOfWindowAndNoExpiry() throws Exception {
        Clinic clinic = createClinic("inv-analytics-exp-" + UUID.randomUUID(), "Expiry Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Penicillin V");

        StockBatch soon = createStockBatch(clinic.getId(), medication.getId(), 20);
        soon.setExpiryDate(LocalDate.now().plusDays(10));
        stockBatchRepository.save(soon);

        StockBatch farOut = createStockBatch(clinic.getId(), medication.getId(), 20);
        farOut.setExpiryDate(LocalDate.now().plusDays(90));
        stockBatchRepository.save(farOut);

        createStockBatch(clinic.getId(), medication.getId(), 20); // no expiry date at all

        mockMvc.perform(get("/api/inventory/analytics").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiringSoon.length()").value(1))
                .andExpect(jsonPath("$.expiringSoon[0].ownerId").value(soon.getId().toString()))
                .andExpect(jsonPath("$.expiringSoon[0].name").value("Penicillin V"));
    }

    @Test
    void lowStockMatchesTheExistingReorderAlertsEndpointForTheSameFixtureData() throws Exception {
        Clinic clinic = createClinic("inv-analytics-low-" + UUID.randomUUID(), "Low Stock Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Warfarin 5mg");
        medication.setReorderThreshold(50);
        medicationRepository.save(medication);
        createStockBatch(clinic.getId(), medication.getId(), 5);

        String reorderAlertsBody = mockMvc.perform(get("/api/inventory/reorder-alerts").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/inventory/analytics").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStock.length()").value(1))
                .andExpect(jsonPath("$.lowStock[0].name").value("Warfarin 5mg"))
                .andExpect(jsonPath("$.lowStock[0].currentQuantity").value(5))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(reorderAlertsBody).contains("Warfarin 5mg");
    }

    @Test
    void assetStatusCountsAggregateCorrectly() throws Exception {
        Clinic clinic = createClinic("inv-analytics-asset-" + UUID.randomUUID(), "Asset Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        createAsset(clinic.getId(), "Autoclave"); // default in_service
        Asset retired = createAsset(clinic.getId(), "Old Scale");
        retired.setStatus("retired");
        assetRepository.save(retired);

        mockMvc.perform(get("/api/inventory/analytics").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetStatusCounts.length()").value(2));
    }

    @Test
    void onlyClinicAdminAndFrontDeskCanReachInventoryAnalytics() throws Exception {
        Clinic clinic = createClinic("inv-analytics-role-" + UUID.randomUUID(), "Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/analytics").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/analytics").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void inventoryAnalyticsIsScopedPerTenant() throws Exception {
        Clinic clinicA = createClinic("inv-analytics-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic clinicB = createClinic("inv-analytics-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Medication medicationA = createMedication(clinicA.getId(), "Amoxicillin");
        medicationA.setUnitPrice(new BigDecimal("2.00"));
        medicationRepository.save(medicationA);
        createStockBatch(clinicA.getId(), medicationA.getId(), 50);
        createAsset(clinicA.getId(), "Autoclave");

        mockMvc.perform(get("/api/inventory/analytics").with(asFrontDesk("fd", clinicB.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalValuation").value(0))
                .andExpect(jsonPath("$.assetStatusCounts.length()").value(0))
                .andExpect(jsonPath("$.expiringSoon.length()").value(0));
    }
}
