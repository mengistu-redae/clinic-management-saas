package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
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

class CheckInIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NoShowScheduler noShowScheduler;

    private record Fixture(Clinic clinic, Provider provider, Patient patient, Appointment appointment) {
    }

    private Fixture seedBookedAppointment(String orgAlias, String clinicName, Instant slotStart, Instant slotEnd, String patientNationalId) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. CheckIn");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Patient patient = createPatient(clinic.getId(), "Check", "In", "+15550003333");
        if (patientNationalId != null) {
            patient.setNationalId(patientNationalId);
            patientRepository.save(patient);
        }
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), slotStart, slotEnd);
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        return new Fixture(clinic, provider, patient, appointment);
    }

    @Test
    void fullHappyPathThroughAllFiveStates() throws Exception {
        Fixture f = seedBookedAppointment("checkin-happy-" + UUID.randomUUID(), "Happy Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), null);
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/check-in").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("checked_in"));
        mockMvc.perform(post("/api/appointments/" + id + "/room").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("roomed"));
        mockMvc.perform(post("/api/appointments/" + id + "/start").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("with_provider"));
        mockMvc.perform(post("/api/appointments/" + id + "/check-out").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("checked_out"));
    }

    @Test
    void reCallingAnAlreadyReachedTransitionIsIdempotent() throws Exception {
        Fixture f = seedBookedAppointment("checkin-idem-" + UUID.randomUUID(), "Idem Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), null);
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        mockMvc.perform(post("/api/appointments/" + id + "/check-in").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk());
        // Re-calling check-in again is a no-op 200, not a 409.
        mockMvc.perform(post("/api/appointments/" + id + "/check-in").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("checked_in"));
    }

    @Test
    void outOfOrderTransitionIsRejected() throws Exception {
        Fixture f = seedBookedAppointment("checkin-order-" + UUID.randomUUID(), "Order Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), null);
        String orgAlias = f.clinic().getKeycloakOrgId();
        String id = f.appointment().getId().toString();

        // room before check-in.
        mockMvc.perform(post("/api/appointments/" + id + "/room").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void checkInAfterTheSlotWindowHasElapsedIsRejected() throws Exception {
        Fixture f = seedBookedAppointment("checkin-late-" + UUID.randomUUID(), "Late Clinic",
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800), null);

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/check-in")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isConflict());
    }

    @Test
    void identityMismatchIsRejected() throws Exception {
        Fixture f = seedBookedAppointment("checkin-mismatch-" + UUID.randomUUID(), "Mismatch Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), "ID-ON-FILE-123");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/check-in")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("WRONG-ID"))))
                .andExpect(status().isConflict());
    }

    @Test
    void noIdOnFileAllowsCheckInThroughEvenWithAPresentedId() throws Exception {
        Fixture f = seedBookedAppointment("checkin-noid-" + UUID.randomUUID(), "No ID Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), null);

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/check-in")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("ANYTHING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("checked_in"));
    }

    @Test
    void matchingPresentedIdSucceeds() throws Exception {
        Fixture f = seedBookedAppointment("checkin-match-" + UUID.randomUUID(), "Match Clinic",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(1800), "MATCHING-ID");

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/check-in")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CheckInRequest("MATCHING-ID"))))
                .andExpect(status().isOk());
    }

    @Test
    void manualNoShowFromBooked() throws Exception {
        Fixture f = seedBookedAppointment("checkin-noshow-" + UUID.randomUUID(), "No Show Clinic",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400), null);

        mockMvc.perform(post("/api/appointments/" + f.appointment().getId() + "/no-show")
                        .with(asFrontDesk("fd", f.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_show"));
    }

    @Test
    void schedulerFlipsOnlyStaleBookedAppointmentsToNoShow() {
        Fixture past = seedBookedAppointment("scheduler-past-" + UUID.randomUUID(), "Past Clinic",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), null);
        Fixture future = seedBookedAppointment("scheduler-future-" + UUID.randomUUID(), "Future Clinic",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400), null);

        noShowScheduler.flipStaleBookedToNoShow();

        assertThat(appointmentRepository.findById(past.appointment().getId()).orElseThrow().getStatus())
                .isEqualTo("no_show");
        assertThat(appointmentRepository.findById(future.appointment().getId()).orElseThrow().getStatus())
                .isEqualTo("booked");
    }
}
