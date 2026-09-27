package com.clinicops.tenant;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.feepolicy.FeePolicy;
import com.clinicops.laborder.CreateLabOrderRequest;
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
        com.clinicops.pharmacy.StockBatch batch = createStockBatch(a.getId(), medication.getId(), 100);

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
                        .content(objectMapper.writeValueAsString(new com.clinicops.pharmacy.WriteOffStockBatchRequest("expired", "cross-tenant probe"))))
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
}
