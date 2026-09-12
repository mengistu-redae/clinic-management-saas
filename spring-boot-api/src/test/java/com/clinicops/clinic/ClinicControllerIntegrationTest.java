package com.clinicops.clinic;

import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the whole phase-1 chain: bearer JWT -> Spring Security ->
 * TenantContextFilter -> @PreAuthorize -> a tenant-scoped repository read.
 * Also covers the clinic-deactivation lockout and the cross-role/cross-
 * tenant boundaries that later domain features will extend in
 * TenantIsolationIntegrationTest.
 */
class ClinicControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void clinicAdminSeesTheirOwnClinic() throws Exception {
        Clinic clinic = createClinic("demo-clinic", "Demo Clinic");

        mockMvc.perform(get("/api/clinic/me").with(asClinicAdmin("admin-1", "demo-clinic")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clinic.getId().toString()))
                .andExpect(jsonPath("$.name").value("Demo Clinic"));
    }

    @Test
    void frontDeskAndProviderCanAlsoReadTheClinic() throws Exception {
        createClinic("demo-clinic-2", "Second Demo Clinic");

        mockMvc.perform(get("/api/clinic/me").with(asFrontDesk("fd-1", "demo-clinic-2")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clinic/me").with(asProvider("prov-1", "demo-clinic-2")))
                .andExpect(status().isOk());
    }

    @Test
    void deactivatedClinicIsLockedOutAtTheFilter() throws Exception {
        Clinic clinic = createClinic("inactive-clinic", "Inactive Clinic");
        clinic.setStatus("inactive");
        clinicRepository.save(clinic);

        mockMvc.perform(get("/api/clinic/me").with(asClinicAdmin("admin-2", "inactive-clinic")))
                .andExpect(status().isForbidden());
    }

    @Test
    void patientTokenHasNoTenantAndIsForbidden() throws Exception {
        // No organization claim at all -> TenantContext stays empty ->
        // @PreAuthorize's role check already rejects this before
        // TenantContext.require() would ever run.
        mockMvc.perform(get("/api/clinic/me").with(asPatient("patient-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminIsNotAmongTheRolesAllowedOnThisStaffEndpoint() throws Exception {
        mockMvc.perform(get("/api/clinic/me").with(asPlatformAdmin("platform-1")))
                .andExpect(status().isForbidden());
    }
}
