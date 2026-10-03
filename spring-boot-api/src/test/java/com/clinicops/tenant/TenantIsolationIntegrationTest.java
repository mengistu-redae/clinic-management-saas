package com.clinicops.tenant;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.feepolicy.FeePolicy;
import com.clinicops.laborder.CreateAnalyteDefinitionRequest;
import com.clinicops.laborder.CreateLabOrderRequest;
import com.clinicops.laborder.CreateQcRunRequest;
import com.clinicops.laborder.SendToReferenceLabRequest;
import com.clinicops.laborder.TestItem;
import com.clinicops.labrate.LabTestRate;
import com.clinicops.patient.Patient;
import com.clinicops.payment.CreatePaymentRequest;
import com.clinicops.provider.Provider;
import com.clinicops.referral.Referral;
import com.clinicops.room.Room;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The consolidated cross-tenant regression guard CLAUDE.md's tenancy-model
 * section has named as "planned, grows with each phase" since phase 1 -
 * pulled together here instead of staying a plan. Each per-resource
 * controller test (RoomControllerIntegrationTest, ProviderControllerIntegrationTest,
 * etc.) already covers its own cross-tenant case in more depth (validation
 * edges, role combinations); this file's job is narrower and different:
 * one canonical "clinic A seeds it, clinic B's staff is refused" check per
 * staff-scoped resource, all in one place, so a newly added resource that
 * forgets tenant scoping has exactly one file to add a line to and one file
 * a reviewer can scan to see the whole tenancy invariant at a glance.
 *
 * Deliberately excluded, not forgotten:
 * - ClinicSettingsController/ClinicBrandingController/the `my-schedule` and
 *   `my-appointments` family - these resolve entirely from the caller's own
 *   token (TenantContext.require() / an ownership id off the JWT), with no
 *   `{id}`-shaped path parameter naming another tenant's row at all. There
 *   is no cross-tenant vector to test here the way there is for a resource
 *   addressed by id.
 * - PlatformController - cross-tenant by design (a platform_admin token
 *   carries no organization claim and legitimately reads every clinic).
 */
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private Clinic clinicA(String label) {
        return createClinic("iso-a-" + label + "-" + UUID.randomUUID(), "Isolation Clinic A (" + label + ")");
    }

    private Clinic clinicB(String label) {
        return createClinic("iso-b-" + label + "-" + UUID.randomUUID(), "Isolation Clinic B (" + label + ")");
    }

    @Test
    void appointmentIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("appointment");
        Provider provider = createProvider(a.getId(), "Dr. A");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(a.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000001");
        Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        Clinic b = clinicB("appointment");
        mockMvc.perform(get("/api/appointments/" + appointment.getId()).with(asFrontDesk("fd", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void patientIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("patient");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000002");

        Clinic b = clinicB("patient");
        mockMvc.perform(get("/api/patients/" + patient.getId()).with(asFrontDesk("fd", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void providerIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("provider");
        Provider provider = createProvider(a.getId(), "Dr. A");

        Clinic b = clinicB("provider");
        mockMvc.perform(get("/api/providers/" + provider.getId()).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void providerWorkingHoursAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("hours");
        Provider provider = createProvider(a.getId(), "Dr. A");

        Clinic b = clinicB("hours");
        mockMvc.perform(get("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/providers/" + provider.getId() + "/working-hours").with(asClinicAdmin("admin", b.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.provider.CreateWorkingHoursRequest(1, LocalTime.of(9, 0), LocalTime.of(17, 0)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void roomIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("room");
        Room room = createRoom(a.getId(), "Room A");

        Clinic b = clinicB("room");
        mockMvc.perform(get("/api/rooms/" + room.getId()).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void appointmentTypeIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("apptype");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");

        Clinic b = clinicB("apptype");
        mockMvc.perform(get("/api/appointment-types/" + type.getId()).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void feePolicyIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("feepolicy");
        FeePolicy policy = createFeePolicy(a.getId(), null, 24, 50);

        Clinic b = clinicB("feepolicy");
        mockMvc.perform(get("/api/fee-policies/" + policy.getId()).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void labTestRateIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("labrate");
        LabTestRate rate = createLabTestRate(a.getId(), "CBC", "20.00", "0.00");

        Clinic b = clinicB("labrate");
        mockMvc.perform(get("/api/clinic/lab-rates/" + rate.getId()).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void encounterIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("encounter");
        Provider provider = createProvider(a.getId(), "Dr. A");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(a.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000003");
        Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        createEncounter(a.getId(), appointment.getId(), provider.getId());

        Clinic b = clinicB("encounter");
        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/encounter").with(asProvider("prov", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void labOrderIsNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("laborder");
        Provider provider = createProvider(a.getId(), "Dr. A");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000004");
        createLabTestRate(a.getId(), "CBC", "20.00", "0.00");

        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());

        Clinic b = clinicB("laborder");
        mockMvc.perform(get("/api/lab-orders/" + orderId).with(asProvider("prov", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    /** Lab module L1 (2026-10-02) - specimens are tenant-scoped the same way every other new resource in this file already is. */
    @Test
    void specimensAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("specimen");
        Provider provider = createProvider(a.getId(), "Dr. A");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000044");
        createLabTestRate(a.getId(), "CBC", "20.00", "0.00");

        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());

        Clinic b = clinicB("specimen");
        mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        String specimensBody = mockMvc.perform(get("/api/lab-orders/" + orderId + "/specimens").with(asLabTechnician("tech", a.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID specimenId = UUID.fromString(objectMapper.readTree(specimensBody).get(0).get("id").asText());

        mockMvc.perform(post("/api/specimens/" + specimenId + "/collect").with(asLabTechnician("tech", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        // Lab module L5 (2026-10-02) - the new send-to-reference-lab action is tenant-scoped the same way.
        mockMvc.perform(post("/api/specimens/" + specimenId + "/send-to-reference-lab").with(asLabTechnician("tech", b.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendToReferenceLabRequest("Outside Labs Inc", null, null))))
                .andExpect(status().isNotFound());
    }

    /** Lab module L2 (2026-10-02) - the analyte catalog and per-analyte results are tenant-scoped the same way. */
    @Test
    void analyteDefinitionsAndResultsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("analyte");
        String bodyDef = mockMvc.perform(post("/api/clinic/analyte-definitions").with(asClinicAdmin("admin", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateAnalyteDefinitionRequest("CBC", "WBC", 1, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID definitionId = UUID.fromString(objectMapper.readTree(bodyDef).get("id").asText());

        Clinic b = clinicB("analyte");
        mockMvc.perform(get("/api/clinic/analyte-definitions/" + definitionId).with(asClinicAdmin("admin", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        Provider provider = createProvider(a.getId(), "Dr. A");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000055");
        createLabTestRate(a.getId(), "CBC", "20.00", "0.00");
        String orderBody = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(objectMapper.readTree(orderBody).get("order").get("id").asText());
        UUID testId = UUID.fromString(objectMapper.readTree(orderBody).get("tests").get(0).get("id").asText());

        mockMvc.perform(get("/api/lab-orders/" + orderId + "/tests/" + testId + "/analyte-results").with(asLabTechnician("tech", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    /**
     * Lab module L4 (2026-10-02) - QC runs have no single-resource GET
     * (list-only), so the isolation check here is "clinic B's own list
     * never includes clinic A's run" rather than a 404 on a shared id -
     * the correct equivalent shape for a list-only resource.
     */
    @Test
    void qcRunsAreNotReadableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("qc");
        mockMvc.perform(post("/api/lab-qc-runs").with(asLabTechnician("tech", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQcRunRequest("ANALYZER-1", "Glucose", "LOT-100",
                                        BigDecimal.valueOf(90), BigDecimal.valueOf(110), "100"))))
                .andExpect(status().isOk());

        Clinic b = clinicB("qc");
        mockMvc.perform(get("/api/lab-qc-runs").with(asLabTechnician("tech", b.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void appointmentAndLabOrderPaymentsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("payment");
        Provider provider = createProvider(a.getId(), "Dr. A");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(a.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000005");
        Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        createLabTestRate(a.getId(), "CBC", "20.00", "0.00");
        String labBody = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID labOrderId = UUID.fromString(objectMapper.readTree(labBody).get("order").get("id").asText());

        Clinic b = clinicB("payment");
        String bAlias = b.getKeycloakOrgId();
        CreatePaymentRequest paymentRequest = new CreatePaymentRequest(new BigDecimal("10.00"), "cash", null, null);

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/payments").with(asProvider("prov", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/lab-orders/" + labOrderId + "/payments").with(asProvider("prov", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/lab-orders/" + labOrderId + "/payments").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    void allergiesAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("allergy");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000006");
        createAllergy(a.getId(), patient.getId(), "Penicillin");

        Clinic b = clinicB("allergy");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/allergies").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.allergy.CreateAllergyRequest("Latex", null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void immunizationsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("immunization");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000008");
        createImmunization(a.getId(), patient.getId(), "Influenza");

        Clinic b = clinicB("immunization");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/immunizations").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.immunization.CreateImmunizationRequest(
                                "Tetanus", java.time.LocalDate.now(), null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void vitalsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("vitals");
        Provider provider = createProvider(a.getId(), "Dr. A");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(a.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000007");
        Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        Clinic b = clinicB("vitals");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/vitals").with(asFrontDesk("fd", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/vitals").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.vitals.UpsertVitalsRequest(
                                null, null, null, 70, null, null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void medicalHistoryIsNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("history");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000008");
        createMedicalHistory(a.getId(), patient.getId());

        Clinic b = clinicB("history");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/medical-history").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.medicalhistory.UpsertMedicalHistoryRequest(
                                "x", null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void consentRecordsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("consent");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000009");
        createConsentRecord(a.getId(), patient.getId(), "general_treatment");

        Clinic b = clinicB("consent");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/consent-records").with(asFrontDesk("fd", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.consent.CreateConsentRecordRequest(
                                "general_treatment", "v1", null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void referralsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("referral");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000010");
        Provider referring = createProvider(a.getId(), "Dr. A");
        Referral referral = createReferral(a.getId(), patient.getId(), referring.getId(), referring.getId());

        Clinic b = clinicB("referral");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/referrals/" + referral.getId()).with(asProvider("prov", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/referrals/" + referral.getId() + "/update").with(asProvider("prov", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.referral.UpdateReferralRequest(
                                "accepted", null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void medicationsAndStockBatchesAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("medication");
        com.clinicops.pharmacy.Medication medication = createMedication(a.getId(), "Amoxicillin 500mg");
        com.clinicops.inventory.StockBatch batch = createStockBatch(a.getId(), medication.getId(), 100);

        Clinic b = clinicB("medication");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/medications/" + medication.getId()).with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/update").with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.UpdateMedicationRequest(
                                null, null, null, null, null, "inactive"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/clinic/medications/" + medication.getId() + "/stock-batches").with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/clinic/medications/" + medication.getId() + "/stock-batches/" + batch.getId() + "/write-off")
                        .with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.WriteOffStockBatchRequest("expired", "cross-tenant probe"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void drugInteractionPairsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("drug-interaction");
        com.clinicops.pharmacy.Medication warfarin = createMedication(a.getId(), "Warfarin");
        com.clinicops.pharmacy.Medication aspirin = createMedication(a.getId(), "Aspirin");
        com.clinicops.pharmacy.DrugInteractionPair pair = createDrugInteractionPair(a.getId(), warfarin.getId(), aspirin.getId());

        Clinic b = clinicB("drug-interaction");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/drug-interaction-pairs/" + pair.getId()).with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/pharmacy/drug-interaction-pairs").with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.CreateDrugInteractionPairRequest(
                                warfarin.getId(), aspirin.getId(), null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void controlledSubstanceRequestsAreNotReadableOrCosignableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("controlled-substance");
        com.clinicops.provider.Provider provider = createProvider(a.getId(), "Dr. Seed");
        com.clinicops.appointmenttype.AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        com.clinicops.scheduling.Slot slot = createSlot(a.getId(), provider.getId(), type.getId(),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(1800));
        com.clinicops.appointment.Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        com.clinicops.encounter.Encounter encounter = createEncounter(a.getId(), appointment.getId(), provider.getId());
        com.clinicops.encounter.Prescription prescription = createPrescription(a.getId(), encounter.getId(), "Oxycodone", null);
        com.clinicops.pharmacy.Medication medication = createMedication(a.getId(), "Oxycodone");
        medication.setControlledSubstanceSchedule("schedule_ii");
        medicationRepository.save(medication);
        com.clinicops.inventory.StockBatch batch = createStockBatch(a.getId(), medication.getId(), 30);

        Clinic b = clinicB("controlled-substance");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/controlled-substance-requests").with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.RequestControlledSubstanceDispenseRequest(
                                medication.getId(), batch.getId(), 5, null, false))))
                .andExpect(status().isNotFound());

        com.clinicops.pharmacy.PendingControlledSubstanceDispense pending = new com.clinicops.pharmacy.PendingControlledSubstanceDispense();
        pending.setTenantId(a.getId());
        pending.setPrescriptionId(prescription.getId());
        pending.setMedicationId(medication.getId());
        pending.setStockBatchId(batch.getId());
        pending.setQuantity(5);
        pending.setRequestedBy(java.util.UUID.randomUUID());
        pending = pendingControlledSubstanceDispenseRepository.save(pending);

        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pending.getId() + "/cosign").with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/pharmacy/controlled-substance-requests/" + pending.getId() + "/reject").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.RejectControlledSubstanceDispenseRequest(null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void inventoryItemsAndTheirStockBatchesAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("inventory-item");
        com.clinicops.inventory.InventoryItem item = createInventoryItem(a.getId(), "Nitrile Gloves");
        com.clinicops.inventory.StockBatch batch = createStockBatchForItem(a.getId(), item.getId(), 50);

        Clinic b = clinicB("inventory-item");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/items/" + item.getId()).with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/items/" + item.getId() + "/update").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.UpdateInventoryItemRequest(
                                null, null, null, null, null, "inactive"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/inventory/items/" + item.getId() + "/stock-batches").with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/items/" + item.getId() + "/stock-batches/" + batch.getId() + "/write-off")
                        .with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.WriteOffStockBatchRequest("expired", "cross-tenant probe"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/stock-batches/" + batch.getId() + "/adjustments").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.CreateStockAdjustmentRequest(-1, "used", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void suppliersAndPurchaseOrdersAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("supplier-po");
        com.clinicops.inventory.Supplier supplier = createSupplier(a.getId(), "MedSupply Co");
        com.clinicops.inventory.PurchaseOrder order = createPurchaseOrder(a.getId(), supplier.getId());
        com.clinicops.inventory.InventoryItem item = createInventoryItem(a.getId(), "Gauze");
        createPurchaseOrderLine(a.getId(), order.getId(), null, item.getId(), 10);

        Clinic b = clinicB("supplier-po");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/suppliers/" + supplier.getId()).with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/inventory/purchase-orders/" + order.getId()).with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/purchase-orders/" + order.getId() + "/receive").with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/purchase-orders/" + order.getId() + "/cancel").with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
    }

    @Test
    void assetsAndTheirMaintenanceRecordsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("asset");
        com.clinicops.inventory.Asset asset = createAsset(a.getId(), "Autoclave");

        Clinic b = clinicB("asset");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/assets/" + asset.getId()).with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/update").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.UpdateAssetRequest(
                                null, null, null, null, null, "retired", null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/assign-room").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.AssignRoomRequest(null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/inventory/assets/" + asset.getId() + "/maintenance-records").with(asClinicAdmin("admin", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/inventory/assets/" + asset.getId() + "/maintenance-records").with(asClinicAdmin("admin", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.inventory.CreateAssetMaintenanceRecordRequest("probe", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void dispensePaymentsAndInvoicesAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("dispense-billing");
        String aAlias = a.getKeycloakOrgId();
        com.clinicops.provider.Provider provider = createProvider(a.getId(), "Dr. Bill");
        com.clinicops.appointmenttype.AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        com.clinicops.scheduling.Slot slot = createSlot(a.getId(), provider.getId(), type.getId(),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(1800));
        com.clinicops.appointment.Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        com.clinicops.encounter.Encounter encounter = createEncounter(a.getId(), appointment.getId(), provider.getId());
        com.clinicops.encounter.Prescription prescription = createPrescription(a.getId(), encounter.getId(), "Amoxicillin", null);
        com.clinicops.pharmacy.Medication medication = createMedication(a.getId(), "Amoxicillin");
        com.clinicops.inventory.StockBatch batch = createStockBatch(a.getId(), medication.getId(), 100);

        String body = mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", aAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.DispenseRequest(medication.getId(), batch.getId(), 10, null, false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID dispenseRecordId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        Clinic b = clinicB("dispense-billing");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/payments").with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/payments").with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.payment.CreatePaymentRequest(
                                new java.math.BigDecimal("10.00"), "cash", null, null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/dispense-records/" + dispenseRecordId + "/invoice").with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
    }

    @Test
    void prescriptionRefillRequestsAreNotApprovableOrDeniableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("refill");
        com.clinicops.user.AppUser appUser = createAppUser("refill-tenant-patient");
        com.clinicops.patient.Patient patient = createPatient(a.getId(), "Refill", "Patient", "+15550004444");
        com.clinicops.provider.Provider provider = createProvider(a.getId(), "Dr. Refill");
        com.clinicops.appointmenttype.AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "50.00");
        com.clinicops.scheduling.Slot slot = createSlot(a.getId(), provider.getId(), type.getId(),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(1800));
        com.clinicops.appointment.Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), appUser.getId());
        com.clinicops.encounter.Encounter encounter = createEncounter(a.getId(), appointment.getId(), provider.getId());
        com.clinicops.encounter.Prescription prescription = createPrescription(a.getId(), encounter.getId(), "Amoxicillin", null);

        String body = mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("refill-tenant-patient"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.CreateRefillRequestRequest(null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID refillId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        Clinic b = clinicB("refill");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/approve").with(asPharmacist("pharm", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/deny").with(asPharmacist("pharm", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.DenyRefillRequestRequest(null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountsAreNotReadableOrWritableFromAnotherTenantAndJournalEntriesAreScopedPerTenant() throws Exception {
        Clinic a = clinicA("account");
        com.clinicops.accounting.Account account = createAccount(a.getId(), "1500", "Isolated Account", "asset");

        Clinic b = clinicB("account");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/accounts/" + account.getId()).with(asAccountant("acct", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/clinic/accounts/" + account.getId() + "/update").with(asAccountant("acct", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.accounting.UpdateAccountRequest(null, "inactive"))))
                .andExpect(status().isNotFound());

        // Clinic B's own trial balance/journal never surfaces clinic A's postings.
        mockMvc.perform(get("/api/clinic/journal-entries").with(asAccountant("acct", bAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void employeesAndBudgetsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("finance");
        com.clinicops.user.AppUser staff = createAppUser("finance-iso-staff");
        com.clinicops.finance.Employee employee = createEmployee(a.getId(), staff.getId(), staff.getEmail(), new java.math.BigDecimal("1000.00"));
        com.clinicops.accounting.Account expense = createAccount(a.getId(), "5000", "Salary Expense", "expense");
        com.clinicops.finance.Budget budget = createBudget(a.getId(), expense.getId(), 2026, 9, new java.math.BigDecimal("500.00"));

        Clinic b = clinicB("finance");
        String bAlias = b.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/employees/" + employee.getId()).with(asAccountant("acct", bAlias)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/clinic/employees/" + employee.getId() + "/update").with(asAccountant("acct", bAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.finance.UpdateEmployeeRequest(null, "inactive"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/clinic/budgets/" + budget.getId()).with(asAccountant("acct", bAlias)))
                .andExpect(status().isNotFound());
    }

    /**
     * The other half of the tenancy invariant: not just "clinic B can't see
     * clinic A's rows" but "a deactivated clinic's own staff are locked out
     * entirely", enforced at TenantContextFilter before any controller ever
     * runs. One representative endpoint is enough here - the filter runs
     * for every /api/** request alike, it isn't a per-controller check.
     */
    @Test
    void aDeactivatedClinicsOwnStaffAreLockedOutAtTheFilter() throws Exception {
        Clinic clinic = createClinic("iso-deactivated-" + UUID.randomUUID(), "Deactivated Clinic");
        clinic.setStatus("inactive");
        clinicRepository.save(clinic);

        mockMvc.perform(get("/api/appointments").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/patients").with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    /** Insurance & claims billing (2026-10-03) - policies and claims are both tenant-scoped the same way every other resource here is. */
    @Test
    void insurancePoliciesAndClaimsAreNotReadableOrWritableFromAnotherTenant() throws Exception {
        Clinic a = clinicA("insurance");
        Provider provider = createProvider(a.getId(), "Dr. A");
        AppointmentType type = createAppointmentType(a.getId(), "Visit", 30, "150.00");
        Patient patient = createPatient(a.getId(), "A", "Patient", "+15550000099");
        Slot slot = createSlot(a.getId(), provider.getId(), type.getId(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));
        Appointment appointment = createBookedAppointment(a.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        com.clinicops.invoice.Invoice invoice = createInvoiceForAppointment(a.getId(), appointment.getId(), new java.math.BigDecimal("150.00"));
        com.clinicops.insurance.InsurancePolicy policy = createInsurancePolicy(a.getId(), patient.getId(), "Acme Health");

        Clinic b = clinicB("insurance");
        mockMvc.perform(get("/api/patients/" + patient.getId() + "/insurance-policies").with(asFrontDesk("fd", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/insurance-policies/" + policy.getId() + "/update").with(asFrontDesk("fd", b.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.insurance.UpdateInsurancePolicyRequest(
                                null, null, null, null, null, null, null, "inactive"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/invoices/" + invoice.getId() + "/claims").with(asFrontDesk("fd", b.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.insurance.CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isNotFound());

        String body = mockMvc.perform(post("/api/invoices/" + invoice.getId() + "/claims").with(asFrontDesk("fd", a.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.insurance.CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID claimId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/claims/" + claimId).with(asFrontDesk("fd", b.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/claims/" + claimId + "/submit").with(asFrontDesk("fd", b.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.clinicops.insurance.SubmitClaimRequest(null))))
                .andExpect(status().isNotFound());
    }
}
