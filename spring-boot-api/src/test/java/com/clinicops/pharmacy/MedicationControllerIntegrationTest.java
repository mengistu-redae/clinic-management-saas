package com.clinicops.pharmacy;

import com.clinicops.clinic.Clinic;
import com.clinicops.inventory.CreateStockBatchRequest;
import com.clinicops.inventory.WriteOffStockBatchRequest;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MedicationControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateAMedicationThenDeactivateIt() throws Exception {
        Clinic clinic = createClinic("med-crud-" + UUID.randomUUID(), "Medication CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/clinic/medications").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateMedicationRequest("Amoxicillin 500mg", "capsule", "capsule", new BigDecimal("2.50"), 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.form").value("capsule"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/medications").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/clinic/medications/" + id + "/update").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateMedicationRequest(null, null, null, null, null, "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(get("/api/clinic/medications").with(asClinicAdmin("admin", orgAlias)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anInvalidFormOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("med-badform-" + UUID.randomUUID(), "Bad Form Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/medications").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateMedicationRequest("Bad Drug", "liquid", null, null, null))))
                .andExpect(status().isBadRequest());

        Medication medication = createMedication(clinic.getId(), "Ibuprofen");
        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/update").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateMedicationRequest(null, null, null, null, null, "deleted"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyPharmacistAndClinicAdminCanWrite() throws Exception {
        Clinic clinic = createClinic("med-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/medications").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMedicationRequest("Paracetamol", null, null, null, null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/clinic/medications").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateMedicationRequest("Paracetamol", null, null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void receivingStockThenWritingItOffZeroesQuantityOnHand() throws Exception {
        Clinic clinic = createClinic("med-batch-" + UUID.randomUUID(), "Batch Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Metformin 500mg");

        String body = mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockBatchRequest("BATCH-1", 50, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(50))
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches/" + batchId + "/write-off")
                        .with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteOffStockBatchRequest("expired", "past expiry date"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"))
                .andExpect(jsonPath("$.quantityOnHand").value(0));
    }

    @Test
    void crossTenantMedicationIsNotFound() throws Exception {
        Clinic clinic = createClinic("med-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("med-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Medication medication = createMedication(clinic.getId(), "Isolated Drug");

        mockMvc.perform(get("/api/clinic/medications/" + medication.getId()).with(asPharmacist("pharm", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void clinicAdminCanSetThenClearTheControlledSubstanceScheduleButPharmacistCannot() throws Exception {
        Clinic clinic = createClinic("med-csched-" + UUID.randomUUID(), "Controlled Substance Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Oxycodone");

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/controlled-substance-schedule").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateControlledSubstanceScheduleRequest("schedule_ii"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/controlled-substance-schedule").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateControlledSubstanceScheduleRequest("schedule_ii"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlledSubstanceSchedule").value("schedule_ii"));

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/controlled-substance-schedule").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateControlledSubstanceScheduleRequest(null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.controlledSubstanceSchedule").doesNotExist());
    }

    @Test
    void anInvalidControlledSubstanceScheduleIsRejected() throws Exception {
        Clinic clinic = createClinic("med-csched-bad-" + UUID.randomUUID(), "Bad Schedule Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Oxycodone");

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/controlled-substance-schedule").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateControlledSubstanceScheduleRequest("schedule_ix"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stockBatchesComeBackEarliestExpiryFirstWithNoExpiryBatchLast() throws Exception {
        Clinic clinic = createClinic("med-fefo-" + UUID.randomUUID(), "FEFO Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");

        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockBatchRequest("NO-EXPIRY", 10, null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockBatchRequest("LATE", 10, java.time.LocalDate.of(2027, 6, 1)))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStockBatchRequest("EARLY", 10, java.time.LocalDate.of(2027, 1, 1)))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchNumber").value("EARLY"))
                .andExpect(jsonPath("$[1].batchNumber").value("LATE"))
                .andExpect(jsonPath("$[2].batchNumber").value("NO-EXPIRY"));
    }
}
