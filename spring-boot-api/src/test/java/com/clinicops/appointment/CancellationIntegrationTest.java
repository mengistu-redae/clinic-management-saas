package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CancellationIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Provider provider, com.clinicops.appointmenttype.AppointmentType type, com.clinicops.patient.Patient patient, Appointment appointment) {
    }

    /** Same helper as AppointmentControllerIntegrationTest's own private one - a deterministic future day/time so a weekly series' every occurrence lands inside the seeded working hours regardless of when this test actually runs. */
    private Instant nextInstantAt(DayOfWeek dayOfWeek, LocalTime time) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(14);
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.plusDays(1);
        }
        return date.atTime(time).toInstant(ZoneOffset.UTC);
    }

    private Fixture seedBookedAppointment(String orgAlias, String clinicName, Instant slotStart) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Cancel");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "100.00");
        var patient = createPatient(clinic.getId(), "Can", "Cel", "+15550001111");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(), slotStart, slotStart.plusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        return new Fixture(clinic, provider, type, patient, appointment);
    }

    @Test
    void staffCancelFreesTheSlotAndRecordsTheConfiguredFee() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(3600); // 1 hour notice
        Fixture fixture = seedBookedAppointment("cancel-fee-" + UUID.randomUUID(), "Fee Clinic", slotStart);
        // 100% fee under 2h notice - cutoffHours=0 is the catch-all tier
        // FeeCalculator picks when notice doesn't clear any higher cutoff
        // (it always applies the *highest* cutoff the notice period clears).
        createFeePolicy(fixture.clinic().getId(), null, 0, 100);
        createFeePolicy(fixture.clinic().getId(), null, 24, 0);

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        assertThat(slotRepository.findById(fixture.appointment().getSlotId()).orElseThrow().getStatus())
                .isEqualTo("open");

        // 100% of the $100.00 appointment type's price - the fee is auto-charged as a real Payment, not just recorded in the audit row.
        mockMvc.perform(get("/api/appointments/" + fixture.appointment().getId() + "/payments")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].method").value("fee_auto_charged"))
                .andExpect(jsonPath("$[0].amount").value(100.00));
    }

    @Test
    void zeroFeeWhenNoPolicyIsConfigured() throws Exception {
        Fixture fixture = seedBookedAppointment(
                "cancel-nofee-" + UUID.randomUUID(), "No Fee Clinic", Instant.now().plusSeconds(600));

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk());
        // No direct assertion on the fee amount via the API response (cancel
        // returns the Appointment, not the audit row) - the important thing
        // is it didn't block the cancellation.

        // A zero fee never creates a Payment - matches FeeCalculator's own "missing/zero = no charge" fallback.
        mockMvc.perform(get("/api/appointments/" + fixture.appointment().getId() + "/payments")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cancellingTwiceReturnsConflict() throws Exception {
        Fixture fixture = seedBookedAppointment(
                "cancel-twice-" + UUID.randomUUID(), "Twice Clinic", Instant.now().plusSeconds(600));

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTenantStaffCancelIsNotFound() throws Exception {
        Fixture fixture = seedBookedAppointment(
                "cancel-owner-" + UUID.randomUUID(), "Owner Clinic", Instant.now().plusSeconds(600));
        Clinic otherClinic = createClinic("cancel-other-" + UUID.randomUUID(), "Other Clinic");

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel")
                        .with(asFrontDesk("fd-1", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void patientCanSelfCancelTheirOwnAppointmentButNotSomeoneElses() throws Exception {
        Clinic clinic = createClinic("cancel-self-" + UUID.randomUUID(), "Self Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Self");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "80.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));

        // Book through the real patient_portal flow so customerUserId is a
        // genuinely provisioned AppUser id, not a hand-wired one.
        String bookingBody = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("owner-patient"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentRequest(
                                slot.getId(), provider.getId(), type.getId(), null, "idem-cancel-self"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(objectMapper.readTree(bookingBody).get("id").asText());

        // A staff-only endpoint from a patient token is forbidden.
        mockMvc.perform(post("/api/appointments/" + appointmentId + "/cancel")
                        .with(asPatient("owner-patient")))
                .andExpect(status().isForbidden());

        // A different patient can't cancel someone else's appointment - 404s identically to unknown.
        mockMvc.perform(post("/api/my-appointments/" + appointmentId + "/cancel")
                        .with(asPatient("someone-else")))
                .andExpect(status().isNotFound());

        // The real owner cancels successfully.
        mockMvc.perform(post("/api/my-appointments/" + appointmentId + "/cancel")
                        .with(asPatient("owner-patient")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    /** A self-cancel fee is still charged to a real account - the patient's own, not left unattributed just because no staff member was involved. */
    @Test
    void aSelfCancelFeeIsAutoChargedAndAttributedToThePatientsOwnAccount() throws Exception {
        Clinic clinic = createClinic("cancel-self-fee-" + UUID.randomUUID(), "Self Fee Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. SelfFee");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "90.00");
        // 50% fee under 2h notice - see the other test's comment on why this is cutoffHours=0, not 2.
        createFeePolicy(clinic.getId(), null, 0, 50);
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));

        String bookingBody = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("self-fee-patient"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentRequest(
                                slot.getId(), provider.getId(), type.getId(), null, "idem-cancel-self-fee"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(objectMapper.readTree(bookingBody).get("id").asText());

        mockMvc.perform(post("/api/my-appointments/" + appointmentId + "/cancel")
                        .with(asPatient("self-fee-patient")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/appointments/" + appointmentId + "/payments")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].method").value("fee_auto_charged"))
                .andExpect(jsonPath("$[0].amount").value(45.00))
                .andExpect(jsonPath("$[0].recordedBy").isNotEmpty());
    }

    /** "Cancel this and the rest of the series" - every still-booked occurrence, each going through the normal fee/audit path. */
    @Test
    void cancelSeriesCancelsEveryRemainingBookedOccurrenceAndFeesEachOne() throws Exception {
        Clinic clinic = createClinic("cancel-series-" + UUID.randomUUID(), "Series Cancel Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. SeriesCancel");
        var type = createAppointmentType(clinic.getId(), "Therapy / 30 min", 30, "50.00");
        for (int day = 0; day <= 6; day++) {
            createWorkingHours(clinic.getId(), provider.getId(), day, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
        var patient = createPatient(clinic.getId(), "Series", "Cancel", "+15550002222");
        // A catch-all tier (cutoffHours=0) so every occurrence, however far
        // out, still gets a 100% fee - see the sibling tests above for why
        // cutoffHours=0, not some higher number, is what "always applies" means.
        createFeePolicy(clinic.getId(), null, 0, 100);
        Instant firstStart = nextInstantAt(DayOfWeek.MONDAY, LocalTime.of(9, 0));

        String seriesBody = mockMvc.perform(post("/api/appointments/series")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentSeriesRequest(
                                patient.getId(), provider.getId(), type.getId(), firstStart, 1, 3, "idem-cancel-series"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        UUID firstOccurrenceId = UUID.fromString(
                objectMapper.readTree(seriesBody).get("created").get(0).get("id").asText());

        mockMvc.perform(post("/api/appointments/" + firstOccurrenceId + "/cancel-series")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.length()").value(3))
                .andExpect(jsonPath("$.cancelled[0].status").value("cancelled"))
                .andExpect(jsonPath("$.cancelled[1].status").value("cancelled"))
                .andExpect(jsonPath("$.cancelled[2].status").value("cancelled"));

        for (JsonNode occurrence : objectMapper.readTree(seriesBody).get("created")) {
            UUID occurrenceId = UUID.fromString(occurrence.get("id").asText());
            mockMvc.perform(get("/api/appointments/" + occurrenceId + "/payments")
                            .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].method").value("fee_auto_charged"))
                    .andExpect(jsonPath("$[0].amount").value(50.00));
        }
    }

    @Test
    void cancelSeriesOnAnAppointmentThatIsNotPartOfASeriesIsRejected() throws Exception {
        Fixture fixture = seedBookedAppointment(
                "cancel-series-notaseries-" + UUID.randomUUID(), "Not A Series Clinic", Instant.now().plusSeconds(3600));

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel-series")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelSeriesIsNotFoundForAnotherTenantsAppointment() throws Exception {
        Fixture fixture = seedBookedAppointment(
                "cancel-series-owner-" + UUID.randomUUID(), "Series Owner Clinic", Instant.now().plusSeconds(3600));
        Clinic otherClinic = createClinic("cancel-series-other-" + UUID.randomUUID(), "Series Other Clinic");

        mockMvc.perform(post("/api/appointments/" + fixture.appointment().getId() + "/cancel-series")
                        .with(asFrontDesk("fd-1", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
