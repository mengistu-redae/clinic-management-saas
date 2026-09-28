package com.clinicops.encounter;

import com.clinicops.appointment.Appointment;
import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EncounterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EncounterRepository encounterRepository;

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    private record Fixture(Clinic clinic, Provider provider, Appointment appointment) {
    }

    /** An appointment already at with_provider - the minimum status encounter documentation is allowed at. */
    private Fixture seedWithProviderAppointment(String orgAlias, String providerSubject) {
        Clinic clinic = createClinic(orgAlias, "Encounter Clinic " + orgAlias);
        Provider provider = createProviderLinkedToAppUser(clinic.getId(), "Dr. Encounter", providerSubject);
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().minusSeconds(600), Instant.now().plusSeconds(600));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        appointment.setStatus("with_provider");
        appointmentRepository.save(appointment);
        return new Fixture(clinic, provider, appointment);
    }

    @Test
    void firstCallCreatesSecondCallUpdatesTheSameRow() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-upsert-" + UUID.randomUUID(), "enc-upsert-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-upsert-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough", "Bronchitis", "Rest + fluids", "J20.9", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chiefComplaint").value("Cough"))
                .andExpect(jsonPath("$.icd10Codes").value("J20.9"));

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-upsert-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough, worse", "Bronchitis", "Antibiotics", "J20.9, R05", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chiefComplaint").value("Cough, worse"))
                .andExpect(jsonPath("$.icd10Codes").value("J20.9, R05"));

        assertThat(encounterRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()))
                .isPresent();
        assertThat(prescriptionRepository.findAllByEncounterId(
                encounterRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()).orElseThrow().getId()))
                .isEmpty();
    }

    @Test
    void examFindingsRoundTripThroughUpsertAndGet() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-exam-" + UUID.randomUUID(), "enc-exam-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-exam-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest(
                                "Palpitations", "Suspected arrhythmia", "ECG ordered", null,
                                null, null, null, null,
                                false, "Irregular rhythm, rate ~110", true, null,
                                null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardiovascularNormal").value(false))
                .andExpect(jsonPath("$.cardiovascularNote").value("Irregular rhythm, rate ~110"))
                .andExpect(jsonPath("$.respiratoryNormal").value(true))
                .andExpect(jsonPath("$.heentNormal").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-exam-provider", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.cardiovascularNormal").value(false))
                .andExpect(jsonPath("$.encounter.cardiovascularNote").value("Irregular rhythm, rate ~110"))
                .andExpect(jsonPath("$.encounter.respiratoryNormal").value(true));
    }

    @Test
    void encounterRejectedBeforeWithProviderStatus() throws Exception {
        Clinic clinic = createClinic("enc-gate-" + UUID.randomUUID(), "Gate Clinic");
        Provider provider = createProviderLinkedToAppUser(clinic.getId(), "Dr. Gate", "enc-gate-provider");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().plusSeconds(600), Instant.now().plusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        // still "booked" - never advanced through check-in/room/start.

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/encounter")
                        .with(asProvider("enc-gate-provider", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void checkedOutStatusStillAllowsDocumentation() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-checkedout-" + UUID.randomUUID(), "enc-checkedout-provider");
        f.appointment().setStatus("checked_out");
        appointmentRepository.save(f.appointment());

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asProvider("enc-checkedout-provider", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void editingAfterCheckedOutRemainsAllowed() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-editafter-" + UUID.randomUUID(), "enc-editafter-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-editafter-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("v1", "v1", "v1", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        f.appointment().setStatus("checked_out");
        appointmentRepository.save(f.appointment());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-editafter-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("v2", "v2", "v2", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chiefComplaint").value("v2"));
    }

    @Test
    void aDifferentProviderCannotDocumentSomeoneElsesAppointment() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-mismatch-" + UUID.randomUUID(), "enc-mismatch-owner");
        createProviderLinkedToAppUser(f.clinic().getId(), "Dr. Other", "enc-mismatch-other");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asProvider("enc-mismatch-other", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void clinicAdminCanDocumentOnBehalfOfAnyProvider() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-admin-" + UUID.randomUUID(), "enc-admin-provider");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asClinicAdmin("admin", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void getBeforeAnyEncounterWrittenIs404() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-get404-" + UUID.randomUUID(), "enc-get404-provider");

        mockMvc.perform(get("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asProvider("enc-get404-provider", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void prescriptionsFullyReplaceNotMerge() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-rx-" + UUID.randomUUID(), "enc-rx-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-rx-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Amoxicillin", "500mg", "3x daily", "oral", "3x daily", "7 days", 21, 0, null),
                                new PrescriptionInput("Ibuprofen", "200mg", "as needed", null, null, null, null, null, null)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].route").value("oral"))
                .andExpect(jsonPath("$[0].quantityDispensed").value(21))
                .andExpect(jsonPath("$[0].status").value("active"));

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Azithromycin", "250mg", "1x daily", null, null, null, null, null, "completed")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].medicationName").value("Azithromycin"))
                .andExpect(jsonPath("$[0].status").value("completed"));

        var encounter = encounterRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()).orElseThrow();
        assertThat(prescriptionRepository.findAllByEncounterId(encounter.getId()))
                .hasSize(1)
                .allSatisfy(rx -> assertThat(rx.getMedicationName()).isEqualTo("Azithromycin"));
    }

    @Test
    void prescriptionsRejectedBeforeAnyEncounterExists() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-rx-noenc-" + UUID.randomUUID(), "enc-rx-noenc-provider");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-noenc-provider", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(new PrescriptionInput("Amoxicillin", "500mg", "3x daily", null, null, null, null, null, null)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anInvalidRouteOrStatusIsRejected() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-rx-invalid-" + UUID.randomUUID(), "enc-rx-invalid-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-rx-invalid-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-invalid-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Amoxicillin", "500mg", "3x daily", "nasal", null, null, null, null, null)))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-invalid-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Amoxicillin", "500mg", "3x daily", null, null, null, null, null, "expired")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantAppointmentIsNotFound() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-tenant-a-" + UUID.randomUUID(), "enc-tenant-a-provider");
        Clinic otherClinic = createClinic("enc-tenant-b-" + UUID.randomUUID(), "Other Clinic");
        createProviderLinkedToAppUser(otherClinic.getId(), "Dr. Other Tenant", "enc-tenant-b-provider");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asProvider("enc-tenant-b-provider", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void signingLocksTheEncounterAndItsPrescriptions() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-sign-" + UUID.randomUUID(), "enc-sign-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough", "Bronchitis", "Rest", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asProvider("enc-sign-provider", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedAt").exists())
                .andExpect(jsonPath("$.signedBy").isNotEmpty());

        // Further direct edits are rejected once signed.
        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough, worse", "Bronchitis", "Antibiotics", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-sign-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Amoxicillin", "500mg", "3x daily", null, null, null, null, null, null)))))
                .andExpect(status().isConflict());

        // An exam-finding-only edit attempt is rejected too - exam findings
        // lock along with the rest of the encounter, not a separate concept.
        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest(
                                "Cough", "Bronchitis", "Rest", null,
                                null, null, null, null, false, "Irregular rhythm", null, null,
                                null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isConflict());

        // The original note is unchanged - still "Cough"/"Rest", not the rejected edit,
        // and the exam finding was never applied (still null).
        mockMvc.perform(get("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-provider", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.chiefComplaint").value("Cough"))
                .andExpect(jsonPath("$.encounter.cardiovascularNormal").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void reSigningAnAlreadySignedEncounterIsIdempotent() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-resign-" + UUID.randomUUID(), "enc-resign-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-resign-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        String firstSignBody = mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asProvider("enc-resign-provider", orgAlias)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstSignedAt = objectMapper.readTree(firstSignBody).get("signedAt").asText();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asProvider("enc-resign-provider", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedAt").value(firstSignedAt));
    }

    @Test
    void cannotSignBeforeAnEncounterExists() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-sign-noenc-" + UUID.randomUUID(), "enc-sign-noenc-provider");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter/sign")
                        .with(asProvider("enc-sign-noenc-provider", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addendumRequiresTheEncounterToAlreadyBeSigned() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-addendum-gate-" + UUID.randomUUID(), "enc-addendum-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-addendum-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        // Not yet signed - addendum rejected, should edit directly instead.
        mockMvc.perform(post("/api/appointments/" + id + "/encounter/addenda")
                        .with(asProvider("enc-addendum-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddendumInput("Correction"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asProvider("enc-addendum-provider", orgAlias)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/addenda")
                        .with(asProvider("enc-addendum-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AddendumInput("Patient called back, symptoms improved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Patient called back, symptoms improved"))
                .andExpect(jsonPath("$.authorId").isNotEmpty());

        mockMvc.perform(get("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-addendum-provider", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addenda.length()").value(1))
                .andExpect(jsonPath("$.addenda[0].text").value("Patient called back, symptoms improved"));
    }

    @Test
    void clinicAdminCanSignOnBehalfOfAnyProvider() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-sign-admin-" + UUID.randomUUID(), "enc-sign-admin-provider");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-admin-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedAt").exists());
    }

    @Test
    void aDifferentProviderCannotSignSomeoneElsesEncounter() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-sign-mismatch-" + UUID.randomUUID(), "enc-sign-mismatch-owner");
        createProviderLinkedToAppUser(f.clinic().getId(), "Dr. Other", "enc-sign-mismatch-other");
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-sign-mismatch-owner", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/sign")
                        .with(asProvider("enc-sign-mismatch-other", orgAlias)))
                .andExpect(status().isForbidden());
    }
}
