package com.clinicops.imaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImagingOrderStatusControllerIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, ImagingOrder order) {
    }

    private Fixture seedOrderedImagingOrder(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Status");
        Patient patient = createPatient(clinic.getId(), "Status", "Patient", "+15554440000");
        ImagingOrder order = createImagingOrder(clinic.getId(), patient.getId(), provider.getId(), "ordered");
        return new Fixture(clinic, provider, order);
    }

    @Test
    void fullLifecycleScheduleStartCompleteReview() throws Exception {
        Fixture fx = seedOrderedImagingOrder("imaging-lifecycle-" + UUID.randomUUID(), "Lifecycle Clinic");
        String orgAlias = fx.clinic().getKeycloakOrgId();
        UUID id = fx.order().getId();

        mockMvc.perform(post("/api/imaging-orders/" + id + "/schedule").with(asImagingTechnologist("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("scheduled"));

        mockMvc.perform(post("/api/imaging-orders/" + id + "/start").with(asImagingTechnologist("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"));

        mockMvc.perform(post("/api/imaging-orders/" + id + "/complete").with(asImagingTechnologist("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));

        mockMvc.perform(post("/api/imaging-orders/" + id + "/review").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewImagingOrderRequest("Clear lungs", "No acute disease", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("reviewed"))
                .andExpect(jsonPath("$.findings").value("Clear lungs"));
    }

    @Test
    void criticalFindingCanBeAcknowledgedByProviderButNotByImagingTechnologist() throws Exception {
        Fixture fx = seedOrderedImagingOrder("imaging-critical-" + UUID.randomUUID(), "Critical Clinic");
        String orgAlias = fx.clinic().getKeycloakOrgId();
        UUID id = fx.order().getId();

        mockMvc.perform(post("/api/imaging-orders/" + id + "/start").with(asImagingTechnologist("tech", orgAlias)));
        mockMvc.perform(post("/api/imaging-orders/" + id + "/complete").with(asImagingTechnologist("tech", orgAlias)));
        mockMvc.perform(post("/api/imaging-orders/" + id + "/review").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReviewImagingOrderRequest("Mass noted", "Suspicious for malignancy", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalFinding").value(true));

        mockMvc.perform(post("/api/imaging-orders/" + id + "/acknowledge-critical").with(asImagingTechnologist("tech", orgAlias)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/imaging-orders/" + id + "/acknowledge-critical").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticalAcknowledgedAt").isNotEmpty());
    }

    @Test
    void cancelIsRejectedOnceInProgress() throws Exception {
        Fixture fx = seedOrderedImagingOrder("imaging-cancel-" + UUID.randomUUID(), "Cancel Clinic");
        String orgAlias = fx.clinic().getKeycloakOrgId();
        UUID id = fx.order().getId();

        mockMvc.perform(post("/api/imaging-orders/" + id + "/start").with(asImagingTechnologist("tech", orgAlias)));

        mockMvc.perform(post("/api/imaging-orders/" + id + "/cancel").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CancelImagingOrderRequest("test"))))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyImagingTechnologistAndClinicAdminCanDriveTheMechanicalSteps() throws Exception {
        Fixture fx = seedOrderedImagingOrder("imaging-mech-roles-" + UUID.randomUUID(), "Mech Roles Clinic");
        String orgAlias = fx.clinic().getKeycloakOrgId();
        UUID id = fx.order().getId();

        mockMvc.perform(post("/api/imaging-orders/" + id + "/schedule").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/imaging-orders/" + id + "/schedule").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void crossTenantScheduleIsNotFound() throws Exception {
        Fixture fx = seedOrderedImagingOrder("imaging-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("imaging-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(post("/api/imaging-orders/" + fx.order().getId() + "/schedule").with(asImagingTechnologist("tech", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
