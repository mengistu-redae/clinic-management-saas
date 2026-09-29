package com.clinicops.analytics;

import com.clinicops.clinic.Clinic;
import com.clinicops.pharmacy.Medication;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PharmacyAnalyticsControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void dispensingVolumeExcludesADispenseOutsideTheWindow() throws Exception {
        Clinic clinic = createClinic("pharm-analytics-vol-" + UUID.randomUUID(), "Volume Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        createDispenseRecord(clinic.getId(), medication.getId(), 10, Instant.now().minus(2, ChronoUnit.DAYS));
        createDispenseRecord(clinic.getId(), medication.getId(), 40, Instant.now().minus(90, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/pharmacy/analytics?days=30").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispensingVolume.length()").value(1))
                .andExpect(jsonPath("$.dispensingVolume[0].total").value(10));
    }

    @Test
    void medicationDispenseCountsAggregateCorrectlyAndEmbedTheName() throws Exception {
        Clinic clinic = createClinic("pharm-analytics-count-" + UUID.randomUUID(), "Count Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication amoxicillin = createMedication(clinic.getId(), "Amoxicillin");
        Medication penicillin = createMedication(clinic.getId(), "Penicillin V");
        createDispenseRecord(clinic.getId(), amoxicillin.getId(), 10, Instant.now());
        createDispenseRecord(clinic.getId(), amoxicillin.getId(), 5, Instant.now());
        createDispenseRecord(clinic.getId(), penicillin.getId(), 20, Instant.now());

        mockMvc.perform(get("/api/pharmacy/analytics?days=30").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicationDispenseCounts.length()").value(2))
                .andExpect(jsonPath("$.medicationDispenseCounts[0].medicationName").value("Amoxicillin"))
                .andExpect(jsonPath("$.medicationDispenseCounts[0].total").value(15))
                .andExpect(jsonPath("$.medicationDispenseCounts[1].medicationName").value("Penicillin V"))
                .andExpect(jsonPath("$.medicationDispenseCounts[1].total").value(20));
    }

    @Test
    void onlyPharmacistAndClinicAdminCanReachPharmacyAnalytics() throws Exception {
        Clinic clinic = createClinic("pharm-analytics-role-" + UUID.randomUUID(), "Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/analytics").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pharmacy/analytics").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void pharmacyAnalyticsIsScopedPerTenant() throws Exception {
        Clinic clinicA = createClinic("pharm-analytics-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic clinicB = createClinic("pharm-analytics-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Medication medicationA = createMedication(clinicA.getId(), "Amoxicillin");
        createDispenseRecord(clinicA.getId(), medicationA.getId(), 10, Instant.now());

        mockMvc.perform(get("/api/pharmacy/analytics").with(asPharmacist("pharm", clinicB.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispensingVolume.length()").value(0))
                .andExpect(jsonPath("$.medicationDispenseCounts.length()").value(0));
    }
}
