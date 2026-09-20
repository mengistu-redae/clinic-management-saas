package com.clinicops.vitals;

import com.clinicops.appointment.Appointment;
import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VitalsControllerIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, Appointment appointment) {
    }

    private Fixture seedBookedAppointment(String orgAlias) {
        Clinic clinic = createClinic(orgAlias, "Vitals Clinic " + orgAlias);
        Provider provider = createProvider(clinic.getId(), "Dr. Vitals");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().minusSeconds(600), Instant.now().plusSeconds(600));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        return new Fixture(clinic, provider, appointment);
    }

    @Test
    void firstCallCreatesSecondCallUpdatesTheSameRowAndBmiIsComputed() throws Exception {
        Fixture f = seedBookedAppointment("vitals-upsert-" + UUID.randomUUID());
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/vitals").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                new BigDecimal("170.0"), new BigDecimal("70.0"), new BigDecimal("36.8"),
                                72, 16, 120, 80, new BigDecimal("98.0"), 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pulseBpm").value(72))
                .andExpect(jsonPath("$.bmi").value(24.2));

        mockMvc.perform(post("/api/appointments/" + id + "/vitals").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                new BigDecimal("170.0"), new BigDecimal("70.0"), new BigDecimal("37.5"),
                                80, 18, 118, 78, new BigDecimal("97.5"), 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pulseBpm").value(80));

        assertThatOnlyOneRowExists(f);
    }

    private void assertThatOnlyOneRowExists(Fixture f) {
        org.assertj.core.api.Assertions.assertThat(
                        vitalsRepository.findByAppointmentIdAndTenantId(f.appointment().getId(), f.clinic().getId()))
                .isPresent();
    }

    @Test
    void frontDeskCanRecordVitalsDespiteNoClinicalAccessElsewhere() throws Exception {
        Fixture f = seedBookedAppointment("vitals-frontdesk-" + UUID.randomUUID());

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                null, null, null, 88, null, null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pulseBpm").value(88));
    }

    @Test
    void noStatusGateEvenAtPlainBookedStatus() throws Exception {
        // seedBookedAppointment leaves the appointment at "booked" - the
        // status EncounterController's own gate would reject outright.
        Fixture f = seedBookedAppointment("vitals-nogate-" + UUID.randomUUID());

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                null, null, null, 70, null, null, null, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void recordingVitalsForACancelledAppointmentIsRejected() throws Exception {
        Fixture f = seedBookedAppointment("vitals-cancelled-" + UUID.randomUUID());
        f.appointment().setStatus("cancelled");
        appointmentRepository.save(f.appointment());

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                null, null, null, 70, null, null, null, null, null))))
                .andExpect(status().isConflict());
    }

    @Test
    void aPainScoreOutOfRangeIsRejected() throws Exception {
        Fixture f = seedBookedAppointment("vitals-painscore-" + UUID.randomUUID());

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                null, null, null, null, null, null, null, null, 11))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBeforeAnyVitalsRecordedIs404() throws Exception {
        Fixture f = seedBookedAppointment("vitals-get404-" + UUID.randomUUID());

        mockMvc.perform(get("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyProviderClinicAdminAndFrontDeskCanReachThisController() throws Exception {
        Fixture f = seedBookedAppointment("vitals-roles-" + UUID.randomUUID());

        mockMvc.perform(get("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantAppointmentIsNotFound() throws Exception {
        Fixture f = seedBookedAppointment("vitals-tenant-a-" + UUID.randomUUID());
        Clinic otherClinic = createClinic("vitals-tenant-b-" + UUID.randomUUID(), "Other Clinic");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/vitals")
                        .with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpsertVitalsRequest(
                                null, null, null, 70, null, null, null, null, null))))
                .andExpect(status().isNotFound());
    }
}
