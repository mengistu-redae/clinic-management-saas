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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough", "Bronchitis", "Rest + fluids"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chiefComplaint").value("Cough"));

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-upsert-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("Cough, worse", "Bronchitis", "Antibiotics"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chiefComplaint").value("Cough, worse"));

        assertThat(encounterRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()))
                .isPresent();
        assertThat(prescriptionRepository.findAllByEncounterId(
                encounterRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()).orElseThrow().getId()))
                .isEmpty();
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("v1", "v1", "v1"))))
                .andExpect(status().isOk());

        f.appointment().setStatus("checked_out");
        appointmentRepository.save(f.appointment());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter")
                        .with(asProvider("enc-editafter-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("v2", "v2", "v2"))))
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void clinicAdminCanDocumentOnBehalfOfAnyProvider() throws Exception {
        Fixture f = seedWithProviderAppointment("enc-admin-" + UUID.randomUUID(), "enc-admin-provider");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/encounter")
                        .with(asClinicAdmin("admin", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Amoxicillin", "500mg", "3x daily"),
                                new PrescriptionInput("Ibuprofen", "200mg", "as needed")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(post("/api/appointments/" + id + "/encounter/prescriptions")
                        .with(asProvider("enc-rx-provider", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                new PrescriptionInput("Azithromycin", "250mg", "1x daily")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].medicationName").value("Azithromycin"));

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
                        .content(objectMapper.writeValueAsString(List.of(new PrescriptionInput("Amoxicillin", "500mg", "3x daily")))))
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
                        .content(objectMapper.writeValueAsString(new UpsertEncounterRequest("x", "y", "z"))))
                .andExpect(status().isNotFound());
    }
}
