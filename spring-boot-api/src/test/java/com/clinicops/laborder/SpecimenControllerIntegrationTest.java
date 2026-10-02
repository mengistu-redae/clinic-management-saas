package com.clinicops.laborder;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The new fine-grained per-specimen path (lab module L1) - collect/mark
 * -in-transit/receive/complete/reject, exercised directly rather than
 * through LabOrderStatusController's own bulk-equivalent actions (those are
 * covered in LabOrderIntegrationTest's own happy-path test, which proves the
 * lockstep sync instead).
 */
class SpecimenControllerIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, Patient patient) {
    }

    private Fixture seed(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Specimen");
        Patient patient = createPatient(clinic.getId(), "Specimen", "Patient", "+15550002222");
        return new Fixture(clinic, provider, patient);
    }

    private UUID createOrderWithBloodAndUrine(Fixture f, String orgAlias) throws Exception {
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "0.00");
        createLabTestRate(f.clinic().getId(), "UA", "10.00", "0.00");
        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("CBC", "Complete Blood Count", "blood", null),
                                        new TestItem("UA", "Urinalysis", "urine", null)),
                                false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());
    }

    @Test
    void twoDistinctSpecimenTypesOnOneOrderDeriveTwoIndependentSpecimens() throws Exception {
        Fixture f = seed("specimen-multi-" + UUID.randomUUID(), "Multi Specimen Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID orderId = createOrderWithBloodAndUrine(f, orgAlias);

        mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("pending_collection"))
                .andExpect(jsonPath("$[1].status").value("pending_collection"));
    }

    @Test
    void oneSpecimenCanAdvanceIndependentlyOfTheOther() throws Exception {
        Fixture f = seed("specimen-independent-" + UUID.randomUUID(), "Independent Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID orderId = createOrderWithBloodAndUrine(f, orgAlias);

        String body = mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        var specimens = objectMapper.readTree(body);
        UUID bloodId = "blood".equals(specimens.get(0).get("specimenType").asText())
                ? UUID.fromString(specimens.get(0).get("id").asText())
                : UUID.fromString(specimens.get(1).get("id").asText());

        mockMvc.perform(post("/api/specimens/" + bloodId + "/collect").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("collected"));

        // The urine specimen is untouched - still pending_collection.
        mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andExpect(jsonPath("$[?(@.specimenType=='urine')].status").value("pending_collection"))
                .andExpect(jsonPath("$[?(@.specimenType=='blood')].status").value("collected"));
    }

    @Test
    void fullFineGrainedLifecycleThroughToCompletion() throws Exception {
        Fixture f = seed("specimen-lifecycle-" + UUID.randomUUID(), "Lifecycle Clinic");
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

        String specimensBody = mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        UUID specimenId = UUID.fromString(objectMapper.readTree(specimensBody).get(0).get("id").asText());

        mockMvc.perform(post("/api/specimens/" + specimenId + "/collect").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("collected"));
        mockMvc.perform(post("/api/specimens/" + specimenId + "/mark-in-transit").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("in_transit"));
        mockMvc.perform(post("/api/specimens/" + specimenId + "/receive").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("received"));
        mockMvc.perform(post("/api/specimens/" + specimenId + "/complete").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"));

        // Out-of-order/already-terminal transitions after completion are rejected.
        mockMvc.perform(post("/api/specimens/" + specimenId + "/collect").with(asLabTechnician("tech", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectingASpecimenRequiresAReasonAndIsTerminal() throws Exception {
        Fixture f = seed("specimen-reject-" + UUID.randomUUID(), "Reject Clinic");
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
        String specimensBody = mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        UUID specimenId = UUID.fromString(objectMapper.readTree(specimensBody).get(0).get("id").asText());

        mockMvc.perform(post("/api/specimens/" + specimenId + "/reject").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/specimens/" + specimenId + "/reject").with(asLabTechnician("tech", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RejectSpecimenRequest("hemolyzed sample"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("hemolyzed sample"));
    }

    @Test
    void onlyLabTechnicianAndClinicAdminCanActOnASpecimen() throws Exception {
        Fixture f = seed("specimen-role-" + UUID.randomUUID(), "Specimen Role Clinic");
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
        String specimensBody = mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        UUID specimenId = UUID.fromString(objectMapper.readTree(specimensBody).get(0).get("id").asText());

        // Read: provider still allowed (widened read gate).
        mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk());
        // Read: front_desk never had lab access at all, still forbidden.
        mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());

        // Write: provider can no longer act on a specimen directly.
        mockMvc.perform(post("/api/specimens/" + specimenId + "/collect").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        // Write: clinic_admin keeps its usual override.
        mockMvc.perform(post("/api/specimens/" + specimenId + "/collect").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk());
    }
}
