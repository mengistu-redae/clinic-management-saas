package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinicsettings.ClinicSettings;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RescheduleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AppointmentReschedulesRepository appointmentReschedulesRepository;

    private record Fixture(Clinic clinic, Provider provider, com.clinicops.appointmenttype.AppointmentType type, Appointment appointment, com.clinicops.scheduling.Slot newSlot) {
    }

    /** A booked appointment with plenty of notice (well beyond the 4h default minimum), plus a second free slot to reschedule into. */
    private Fixture seedReschedulableAppointment(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Reschedule");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "60.00");
        Instant oldStart = Instant.now().plusSeconds(48 * 3600); // 48h notice
        var oldSlot = createSlot(clinic.getId(), provider.getId(), type.getId(), oldStart, oldStart.plusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), oldSlot.getId(), null, provider.getId(), type.getId(), null);
        Instant newStart = oldStart.plusSeconds(3600);
        var newSlot = createSlot(clinic.getId(), provider.getId(), type.getId(), newStart, newStart.plusSeconds(1800));
        return new Fixture(clinic, provider, type, appointment, newSlot);
    }

    @Test
    void staffReschedulesToANewSlotAndFreesTheOld() throws Exception {
        Fixture fixture = seedReschedulableAppointment("resched-" + UUID.randomUUID(), "Resched Clinic");
        UUID oldSlotId = fixture.appointment().getSlotId();

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/reschedule")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                fixture.newSlot().getId(), fixture.provider().getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(fixture.newSlot().getId().toString()));

        assertThat(slotRepository.findById(oldSlotId).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(slotRepository.findById(fixture.newSlot().getId()).orElseThrow().getStatus()).isEqualTo("booked");
        assertThat(appointmentReschedulesRepository.findAllByAppointmentId(fixture.appointment().getId()))
                .hasSize(1)
                .allSatisfy(audit -> assertThat(audit.getPreviousSlotId()).isEqualTo(oldSlotId));
    }

    @Test
    void reschedulingInsideTheNoticeWindowIsRejected() throws Exception {
        Clinic clinic = createClinic("resched-late-" + UUID.randomUUID(), "Late Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Late");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "60.00");
        Instant soon = Instant.now().plusSeconds(2 * 3600); // 2h notice - under the 4h default minimum
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), soon, soon.plusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        var newSlot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                soon.plusSeconds(3600), soon.plusSeconds(5400));

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/reschedule")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                newSlot.getId(), provider.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotRescheduleIntoAnotherClinicsSlot() throws Exception {
        Fixture fixture = seedReschedulableAppointment("resched-mismatch-" + UUID.randomUUID(), "Mismatch Clinic");
        Clinic otherClinic = createClinic("resched-other-" + UUID.randomUUID(), "Other Clinic");
        Provider otherProvider = createProvider(otherClinic.getId(), "Dr. Other");
        var otherType = createAppointmentType(otherClinic.getId(), "Visit", 30, "60.00");
        Instant start = Instant.now().plusSeconds(50 * 3600);
        var otherSlot = createSlot(otherClinic.getId(), otherProvider.getId(), otherType.getId(), start, start.plusSeconds(1800));

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/reschedule")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                otherSlot.getId(), otherProvider.getId()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reschedulingIntoAnAlreadyBookedSlotConflicts() throws Exception {
        Fixture fixture = seedReschedulableAppointment("resched-conflict-" + UUID.randomUUID(), "Conflict Clinic");
        fixture.newSlot().setStatus("booked");
        slotRepository.save(fixture.newSlot());

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/reschedule")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                fixture.newSlot().getId(), fixture.provider().getId()))))
                .andExpect(status().isConflict());
    }

    /** Proves ClinicSettingsService.resolve is actually wired into RescheduleService, not just present - see CLAUDE.md's phase 5 write-up. */
    @Test
    void aClinicSettingsOverrideActuallyChangesTheNoticeGate() throws Exception {
        Clinic clinic = createClinic("resched-override-" + UUID.randomUUID(), "Override Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Override");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "60.00");

        ClinicSettings settings = new ClinicSettings();
        settings.setTenantId(clinic.getId());
        settings.setRescheduleMinNoticeHours(48);
        clinicSettingsRepository.save(settings);

        // 24h notice - clears the platform default (4h) but not this clinic's overridden 48h minimum.
        Instant start = Instant.now().plusSeconds(24 * 3600);
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), start, start.plusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), null, provider.getId(), type.getId(), null);
        var newSlot = createSlot(clinic.getId(), provider.getId(), type.getId(), start.plusSeconds(3600), start.plusSeconds(5400));

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/reschedule")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                newSlot.getId(), provider.getId()))))
                .andExpect(status().isConflict());
    }

    @Test
    void patientSelfReschedulesTheirOwnAppointment() throws Exception {
        Clinic clinic = createClinic("resched-self-" + UUID.randomUUID(), "Self Resched Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. SelfR");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "60.00");
        Instant start = Instant.now().plusSeconds(48 * 3600);
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), start, start.plusSeconds(1800));
        var newSlot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                start.plusSeconds(3600), start.plusSeconds(5400));

        String bookingBody = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("resched-patient"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentRequest(
                                slot.getId(), provider.getId(), type.getId(), null, "idem-resched-self"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(objectMapper.readTree(bookingBody).get("id").asText());

        mockMvc.perform(post("/api/my-appointments/" + appointmentId + "/reschedule")
                        .with(asPatient("resched-patient"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RescheduleAppointmentRequest(
                                newSlot.getId(), provider.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(newSlot.getId().toString()));
    }
}
