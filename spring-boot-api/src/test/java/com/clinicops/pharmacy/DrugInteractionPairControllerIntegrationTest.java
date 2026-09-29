package com.clinicops.pharmacy;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DrugInteractionPairControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListUpdateAndDeleteADrugInteractionPair() throws Exception {
        Clinic clinic = createClinic("dip-crud-" + UUID.randomUUID(), "Interaction CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");
        Medication aspirin = createMedication(clinic.getId(), "Aspirin");

        String body = mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDrugInteractionPairRequest(
                                warfarin.getId(), aspirin.getId(), "severe", "Increased bleeding risk"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("severe"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateDrugInteractionPairRequest("moderate", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("moderate"))
                .andExpect(jsonPath("$.description").value("Increased bleeding risk"));

        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs/" + id + "/delete").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDuplicatePairInEitherOrderIsRejected() throws Exception {
        Clinic clinic = createClinic("dip-dup-" + UUID.randomUUID(), "Dup Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");
        Medication aspirin = createMedication(clinic.getId(), "Aspirin");
        createDrugInteractionPair(clinic.getId(), warfarin.getId(), aspirin.getId());

        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDrugInteractionPairRequest(warfarin.getId(), aspirin.getId(), null, null))))
                .andExpect(status().isConflict());

        // Reversed order is still the same conflict.
        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDrugInteractionPairRequest(aspirin.getId(), warfarin.getId(), null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void aSelfPairIsRejected() throws Exception {
        Clinic clinic = createClinic("dip-self-" + UUID.randomUUID(), "Self Pair Clinic");
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");

        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDrugInteractionPairRequest(warfarin.getId(), warfarin.getId(), null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownMedicationIs404() throws Exception {
        Clinic clinic = createClinic("dip-unknown-" + UUID.randomUUID(), "Unknown Med Clinic");
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");

        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDrugInteractionPairRequest(warfarin.getId(), UUID.randomUUID(), null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantPairIsNotFound() throws Exception {
        Clinic clinic = createClinic("dip-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("dip-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Medication warfarin = createMedication(clinic.getId(), "Warfarin");
        Medication aspirin = createMedication(clinic.getId(), "Aspirin");
        DrugInteractionPair pair = createDrugInteractionPair(clinic.getId(), warfarin.getId(), aspirin.getId());

        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs/" + pair.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void frontDeskAndProviderCannotReachDrugInteractionPairs() throws Exception {
        Clinic clinic = createClinic("dip-role-" + UUID.randomUUID(), "Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }
}
