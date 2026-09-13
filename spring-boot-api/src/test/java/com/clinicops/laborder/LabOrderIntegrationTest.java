package com.clinicops.laborder;

import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LabOrderIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, Patient patient) {
    }

    private Fixture seedClinicProviderPatient(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Lab");
        Patient patient = createPatient(clinic.getId(), "Lab", "Patient", "+15550001111");
        return new Fixture(clinic, provider, patient);
    }

    @Test
    void createsAndPricesAMultiTestOrder() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-create-" + UUID.randomUUID(), "Create Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "5.00");
        createLabTestRate(f.clinic().getId(), "LFT", "30.00", "0.00");

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), "routine check", null,
                                List.of(new TestItem("CBC", "Complete Blood Count", "blood", null),
                                        new TestItem("LFT", "Liver Function Test", "blood", null)),
                                false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("ordered"))
                .andExpect(jsonPath("$.order.totalCost").value(55.00))
                .andExpect(jsonPath("$.tests.length()").value(2));
    }

    @Test
    void missingRateBlocksCreation() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-norate-" + UUID.randomUUID(), "No Rate Clinic");

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("UNCONFIGURED", "Unconfigured Test", null, null)), false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyTestsListIsRejectedByValidation() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-empty-" + UUID.randomUUID(), "Empty Clinic");

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null, List.of(), false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restrictedTestIsRejectedUnlessConsentAcknowledged() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-restricted-" + UUID.randomUUID(), "Restricted Clinic");
        createLabTestRate(f.clinic().getId(), "HIV-1", "40.00", "0.00");
        String orgAlias = f.clinic().getKeycloakOrgId();

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("HIV-1", "HIV Test", "blood", null)), false))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("HIV-1", "HIV Test", "blood", null)), true))))
                .andExpect(status().isOk());
    }

    @Test
    void encounterFromADifferentPatientIsRejected() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-encmismatch-" + UUID.randomUUID(), "Enc Mismatch Clinic");
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "0.00");
        var type = createAppointmentType(f.clinic().getId(), "Visit", 30, "50.00");
        var slot = createSlot(f.clinic().getId(), f.provider().getId(), type.getId(), Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800));
        Patient otherPatient = createPatient(f.clinic().getId(), "Other", "Patient", "+15559998888");
        var appointment = createBookedAppointment(f.clinic().getId(), slot.getId(), otherPatient.getId(), f.provider().getId(), type.getId(), null);
        Encounter encounter = createEncounter(f.clinic().getId(), appointment.getId(), f.provider().getId());

        mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), encounter.getId(), f.provider().getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isBadRequest());
    }

    private UUID createOrderedOrder(Fixture f, String orgAlias) throws Exception {
        createLabTestRate(f.clinic().getId(), "CBC", "20.00", "0.00");
        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                f.patient().getId(), null, f.provider().getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());
    }

    @Test
    void fullHappyPathThroughAllStatuses() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-happy-" + UUID.randomUUID(), "Happy Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        f.patient().setNationalId("ID-ON-FILE-777");
        patientRepository.save(f.patient());
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/collect-specimen").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("ID-ON-FILE-777"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("specimen_collected"));

        mockMvc.perform(post("/api/lab-orders/" + id + "/send").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("in_transit"));

        String getBody = mockMvc.perform(get("/api/lab-orders/" + id).with(asProvider("prov", orgAlias)))
                .andReturn().getResponse().getContentAsString();
        UUID testId = UUID.fromString(objectMapper.readTree(getBody).get("tests").get(0).get("id").asText());

        mockMvc.perform(post("/api/lab-orders/" + id + "/result").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResultLabOrderRequest(
                                List.of(new TestResultInput(testId, "5.4", "x10^9/L", "4.0-11.0", false))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("resulted"))
                .andExpect(jsonPath("$.tests[0].resultValue").value("5.4"));

        // Resulted-but-unreviewed values are readable by a second provider.
        mockMvc.perform(get("/api/lab-orders/" + id).with(asProvider("second-prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests[0].resultValue").value("5.4"));

        mockMvc.perform(post("/api/lab-orders/" + id + "/review").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("reviewed"));

        // Idempotent re-calls.
        mockMvc.perform(post("/api/lab-orders/" + id + "/review").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("reviewed"));
    }

    @Test
    void outOfOrderTransitionIsRejected() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-order-order-" + UUID.randomUUID(), "Order Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/send").with(asProvider("prov", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void collectSpecimenIdentityMismatchIsRejected() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-idmismatch-" + UUID.randomUUID(), "ID Mismatch Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        f.patient().setNationalId("ID-ON-FILE-999");
        patientRepository.save(f.patient());
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/collect-specimen").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("WRONG-ID"))))
                .andExpect(status().isConflict());
    }

    /** Deliberately the opposite of check-in's convention - no ID on file is ALSO a mismatch here. */
    @Test
    void collectSpecimenWithNoIdOnFileIsAlsoRejected() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-noid-" + UUID.randomUUID(), "No ID Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/collect-specimen").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("ANYTHING"))))
                .andExpect(status().isConflict());
    }

    @Test
    void testListPatchReplacesWhileOrderedButRejectedAfterCollection() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-patch-" + UUID.randomUUID(), "Patch Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        createLabTestRate(f.clinic().getId(), "LFT", "30.00", "0.00");
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/update").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLabOrderRequest(
                                null, null, null, null, List.of(new TestItem("LFT", "Liver Function", "blood", null)), false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests.length()").value(1))
                .andExpect(jsonPath("$.tests[0].testCode").value("LFT"));

        mockMvc.perform(post("/api/lab-orders/" + id + "/update").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLabOrderRequest(null, null, null, null, List.of(), false))))
                .andExpect(status().isBadRequest());

        f.patient().setNationalId("ID-999");
        patientRepository.save(f.patient());
        mockMvc.perform(post("/api/lab-orders/" + id + "/collect-specimen").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("ID-999"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lab-orders/" + id + "/update").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateLabOrderRequest(null, null, "note change", null, null, false))))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellationFeeUsesTheZeroCutoffTierOrZeroWhenNoneConfigured() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-cancel-zero-" + UUID.randomUUID(), "Cancel Zero Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        UUID id = createOrderedOrder(f, orgAlias);

        mockMvc.perform(post("/api/lab-orders/" + id + "/cancel").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("cancelled"));

        Fixture f2 = seedClinicProviderPatient("lab-cancel-fee-" + UUID.randomUUID(), "Cancel Fee Clinic");
        String orgAlias2 = f2.clinic().getKeycloakOrgId();
        createFeePolicy(f2.clinic().getId(), null, 0, 100);
        UUID id2 = createOrderedOrder(f2, orgAlias2);

        mockMvc.perform(post("/api/lab-orders/" + id2 + "/cancel").with(asProvider("prov", orgAlias2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("cancelled"));
    }

    @Test
    void cannotCancelAfterSpecimenCollected() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-cancel-late-" + UUID.randomUUID(), "Cancel Late Clinic");
        String orgAlias = f.clinic().getKeycloakOrgId();
        f.patient().setNationalId("ID-1");
        patientRepository.save(f.patient());
        UUID id = createOrderedOrder(f, orgAlias);
        mockMvc.perform(post("/api/lab-orders/" + id + "/collect-specimen").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CollectSpecimenRequest("ID-1"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lab-orders/" + id + "/cancel").with(asProvider("prov", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTenantLabOrderIsNotFound() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("lab-tenant-b-" + UUID.randomUUID(), "Clinic B");
        UUID id = createOrderedOrder(f, f.clinic().getKeycloakOrgId());

        mockMvc.perform(get("/api/lab-orders/" + id).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void frontDeskCannotReadLabOrders() throws Exception {
        Fixture f = seedClinicProviderPatient("lab-role-" + UUID.randomUUID(), "Role Clinic");

        mockMvc.perform(get("/api/lab-orders").with(asFrontDesk("fd", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
