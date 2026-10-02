package com.clinicops.laborder;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyteResultControllerIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, Patient patient) {
    }

    private Fixture seed(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Analyte");
        Patient patient = createPatient(clinic.getId(), "Analyte", "Patient", "+15550003333");
        return new Fixture(clinic, provider, patient);
    }

    /** Creates a CBC order and advances it to in_transit (the earliest status analyte results are enterable at), returning {orderId, testId}. */
    private UUID[] createInTransitOrder(Fixture f, String orgAlias) throws Exception {
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "0.00");
        String orderBody = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(orderBody).get("order").get("id").asText());
        UUID testId = UUID.fromString(objectMapper.readTree(orderBody).get("tests").get(0).get("id").asText());

        f.patient().setNationalId("ID-ANALYTE");
        patientRepository.save(f.patient());
        mockMvc.perform(post("/api/lab-orders/" + orderId + "/collect-specimen").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("ID-ANALYTE"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/lab-orders/" + orderId + "/send").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk());

        return new UUID[] {orderId, testId};
    }

    @Test
    void enteringStructuredResultsAutoFlagsAgainstTheCatalogDefinition() throws Exception {
        Fixture f = seed("analyte-flag-" + UUID.randomUUID(), "Analyte Flag Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        mockMvc.perform(post("/api/clinic/analyte-definitions").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, "x10^9/L", BigDecimal.valueOf(4.0), BigDecimal.valueOf(11.0), null))))
                .andExpect(status().isOk());
        UUID[] ids = createInTransitOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + ids[0] + "/tests/" + ids[1] + "/analyte-results").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EnterAnalyteResultsRequest(List.of(new AnalyteResultInput("WBC", "15.0"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flag").value("abnormal"))
                .andExpect(jsonPath("$[0].referenceRangeDisplay").value("4.0-11.0"));

        mockMvc.perform(get("/api/lab-orders/" + ids[0] + "/tests/" + ids[1] + "/analyte-results").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("15.0"));
    }

    @Test
    void theOldFlatResultEndpointStillWorksUnchangedAndStructuredResultsDoNotRequireIt() throws Exception {
        Fixture f = seed("analyte-coexist-" + UUID.randomUUID(), "Analyte Coexist Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID[] ids = createInTransitOrder(f, orgAlias);

        // Enter a structured result, then flip the order to "resulted" with an
        // empty flat-results list - no flat TestResultInput needed at all.
        mockMvc.perform(post("/api/lab-orders/" + ids[0] + "/tests/" + ids[1] + "/analyte-results").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EnterAnalyteResultsRequest(List.of(new AnalyteResultInput("WBC", "6.0"))))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lab-orders/" + ids[0] + "/result").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResultLabOrderRequest(List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("resulted"))
                // The old flat field on the test line is untouched - still null.
                .andExpect(jsonPath("$.tests[0].resultValue").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void enteringResultsBeforeSpecimenSentIs409() throws Exception {
        Fixture f = seed("analyte-early-" + UUID.randomUUID(), "Analyte Early Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "0.00");
        String orderBody = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(orderBody).get("order").get("id").asText());
        UUID testId = UUID.fromString(objectMapper.readTree(orderBody).get("tests").get(0).get("id").asText());

        mockMvc.perform(post("/api/lab-orders/" + orderId + "/tests/" + testId + "/analyte-results").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EnterAnalyteResultsRequest(List.of(new AnalyteResultInput("WBC", "6.0"))))))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyLabTechnicianAndClinicAdminCanEnterResults() throws Exception {
        Fixture f = seed("analyte-role-" + UUID.randomUUID(), "Analyte Role Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID[] ids = createInTransitOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + ids[0] + "/tests/" + ids[1] + "/analyte-results").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new EnterAnalyteResultsRequest(List.of(new AnalyteResultInput("WBC", "6.0"))))))
                .andExpect(status().isForbidden());

        // Read still works for provider (widened gate matches LabOrderController's own).
        mockMvc.perform(get("/api/lab-orders/" + ids[0] + "/tests/" + ids[1] + "/analyte-results").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk());
    }
}
