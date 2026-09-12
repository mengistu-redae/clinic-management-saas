package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * The appointment-booking flow (patient_portal/front_desk/guest channels,
 * idempotency, slot conflict, cross-tenant, clinic-inactive, public
 * tracking, patient auto-provisioning) through the real filter chain and
 * the real SlotLockService/AppointmentWriter split, against Postgres+Redis.
 */
class AppointmentControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private com.clinicops.patient.PatientRepository patientRepository;

    /** Seeds a clinic with one provider offering one appointment type on every weekday, plus an already-generated open slot next Monday 9am. */
    private Fixture seedClinicWithOpenSlot(String orgAlias, String clinicName) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Demo");
        var type = createAppointmentType(clinic.getId(), "New Patient / 30 min", 30, "50.00");
        for (int day = 0; day <= 6; day++) {
            createWorkingHours(clinic.getId(), provider.getId(), day, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
        Instant nextSlotStart = nextInstantAt(DayOfWeek.MONDAY, LocalTime.of(9, 0));
        var slot = new com.clinicops.scheduling.Slot();
        slot.setTenantId(clinic.getId());
        slot.setProviderId(provider.getId());
        slot.setAppointmentTypeId(type.getId());
        slot.setStartTime(nextSlotStart);
        slot.setEndTime(nextSlotStart.plusSeconds(1800));
        slot = slotRepository.save(slot);
        return new Fixture(clinic, provider, type, slot);
    }

    @Autowired
    private com.clinicops.scheduling.SlotRepository slotRepository;

    private record Fixture(Clinic clinic, Provider provider, com.clinicops.appointmenttype.AppointmentType type, com.clinicops.scheduling.Slot slot) {
    }

    private Instant nextInstantAt(DayOfWeek dayOfWeek, LocalTime time) {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(14); // safely in the future regardless of today
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.plusDays(1);
        }
        return date.atTime(time).toInstant(ZoneOffset.UTC);
    }

    @Test
    void patientPortalBookingAutoProvisionsAPatientRecordOnFirstBookingAtThisClinic() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-portal-" + UUID.randomUUID(), "Portal Clinic");

        var request = new CreateAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(), null, "idem-portal-1");

        String body = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("patient-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("patient_portal"))
                .andExpect(jsonPath("$.status").value("booked"))
                .andExpect(jsonPath("$.appointmentRef").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID patientId = UUID.fromString(objectMapper.readTree(body).get("patientId").asText());
        var patient = patientRepository.findById(patientId).orElseThrow();
        assertThat(patient.getTenantId()).isEqualTo(fixture.clinic().getId());
        assertThat(patient.getAppUserId()).isNotNull();

        // A second booking by the same portal user at the same clinic
        // reuses the same Patient row rather than creating a second one.
        Fixture fixture2 = new Fixture(fixture.clinic(), fixture.provider(), fixture.type(),
                secondSlot(fixture));
        var secondRequest = new CreateAppointmentRequest(
                fixture2.slot().getId(), fixture.provider().getId(), fixture.type().getId(), null, "idem-portal-2");
        String body2 = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("patient-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID patientId2 = UUID.fromString(objectMapper.readTree(body2).get("patientId").asText());
        assertThat(patientId2).isEqualTo(patientId);
    }

    private com.clinicops.scheduling.Slot secondSlot(Fixture fixture) {
        Instant start = nextInstantAt(DayOfWeek.MONDAY, LocalTime.of(10, 0));
        var slot = new com.clinicops.scheduling.Slot();
        slot.setTenantId(fixture.clinic().getId());
        slot.setProviderId(fixture.provider().getId());
        slot.setAppointmentTypeId(fixture.type().getId());
        slot.setStartTime(start);
        slot.setEndTime(start.plusSeconds(1800));
        return slotRepository.save(slot);
    }

    @Test
    void frontDeskBooksOnBehalfOfAnExistingPatient() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-fd-" + UUID.randomUUID(), "Front Desk Clinic");
        var patient = createPatient(fixture.clinic().getId(), "Walk", "In", "+15551230000");

        var request = new CreateAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(), patient.getId(), "idem-fd-1");

        mockMvc.perform(post("/api/appointments")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("front_desk"))
                .andExpect(jsonPath("$.patientId").value(patient.getId().toString()));
    }

    @Test
    void frontDeskBookingWithoutAPatientIdIsRejected() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-fd-nopatient-" + UUID.randomUUID(), "No Patient Clinic");
        var request = new CreateAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(), null, "idem-fd-nopatient");

        mockMvc.perform(post("/api/appointments")
                        .with(asFrontDesk("fd-1", fixture.clinic().getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void frontDeskCannotBookAnotherClinicsSlot() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-mismatch-owner-" + UUID.randomUUID(), "Owner Clinic");
        Clinic otherClinic = createClinic("appt-mismatch-other-" + UUID.randomUUID(), "Other Clinic");
        var patient = createPatient(fixture.clinic().getId(), "Walk", "In", "+15551230000");

        var request = new CreateAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(), patient.getId(), "idem-mismatch");

        mockMvc.perform(post("/api/appointments")
                        .with(asFrontDesk("fd-1", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestBooksWithNoAccountAndCanTrackItByRefAndPhone() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-guest-" + UUID.randomUUID(), "Guest Clinic");
        var request = new CreateGuestAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(),
                "Guest Person", "+15559998888", "guest@example.test", "idem-guest-1");

        String body = mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("guest"))
                .andReturn().getResponse().getContentAsString();
        String ref = objectMapper.readTree(body).get("appointmentRef").asText();

        mockMvc.perform(get("/api/appointments/track/" + ref).param("phone", "+15559998888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentRef").value(ref))
                .andExpect(jsonPath("$.status").value("booked"))
                .andExpect(jsonPath("$.clinicName").value("Guest Clinic"));

        // A mismatched phone or unknown ref both 404 identically.
        mockMvc.perform(get("/api/appointments/track/" + ref).param("phone", "+10000000000"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/appointments/track/ZZZZZZ").param("phone", "+15559998888"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retryingWithTheSameIdempotencyKeyReturnsTheOriginalAppointment() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-idem-" + UUID.randomUUID(), "Idem Clinic");
        var request = new CreateGuestAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(),
                "Guest Person", "+15559998888", null, "idem-repeat");

        String first = mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id").asText())
                .isEqualTo(objectMapper.readTree(first).get("id").asText());
        assertThat(appointmentRepository.findAllByTenantId(fixture.clinic().getId())).hasSize(1);
    }

    @Test
    void bookingAnAlreadyBookedSlotReturnsConflict() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-conflict-" + UUID.randomUUID(), "Conflict Clinic");
        var first = new CreateGuestAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(),
                "First", "+15551110000", null, "idem-conflict-a");
        var second = new CreateGuestAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(),
                "Second", "+15552220000", null, "idem-conflict-b");

        mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isConflict());
    }

    @Test
    void bookingIsBlockedWhenTheClinicIsDeactivated() throws Exception {
        Fixture fixture = seedClinicWithOpenSlot("appt-inactive-" + UUID.randomUUID(), "Inactive Clinic");
        Clinic clinic = fixture.clinic();
        clinic.setStatus("inactive");
        clinicRepository.save(clinic);

        var request = new CreateGuestAppointmentRequest(
                fixture.slot().getId(), fixture.provider().getId(), fixture.type().getId(),
                "Guest", "+15559998888", null, "idem-inactive");

        mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        // The slot was never actually locked/booked - still open once reactivated.
        assertThat(slotRepository.findById(fixture.slot().getId()).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void availabilitySearchGeneratesAndReturnsOpenSlots() throws Exception {
        Clinic clinic = createClinic("appt-avail-" + UUID.randomUUID(), "Availability Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Availability");
        var type = createAppointmentType(clinic.getId(), "Follow-up / 15 min", 15, "20.00");
        for (int day = 0; day <= 6; day++) {
            createWorkingHours(clinic.getId(), provider.getId(), day, LocalTime.of(9, 0), LocalTime.of(10, 0));
        }

        mockMvc.perform(get("/api/clinics/" + clinic.getId() + "/availability")
                        .param("providerId", provider.getId().toString())
                        .param("appointmentTypeId", type.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void recurringSeriesCreatesEveryOccurrenceWhenAllSlotsAreFree() throws Exception {
        Clinic clinic = createClinic("appt-series-" + UUID.randomUUID(), "Series Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Series");
        var type = createAppointmentType(clinic.getId(), "Therapy / 30 min", 30, "40.00");
        for (int day = 0; day <= 6; day++) {
            createWorkingHours(clinic.getId(), provider.getId(), day, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
        var patient = createPatient(clinic.getId(), "Series", "Patient", "+15550001111");
        Instant firstStart = nextInstantAt(DayOfWeek.MONDAY, LocalTime.of(9, 0));

        var request = new CreateAppointmentSeriesRequest(
                patient.getId(), provider.getId(), type.getId(), firstStart, 1, 3, "idem-series-1");

        mockMvc.perform(post("/api/appointments/series")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created.length()").value(3))
                .andExpect(jsonPath("$.conflicts.length()").value(0));
    }

    @Test
    void recurringSeriesReportsAPartialConflictAndStaysIdempotentOnRetry() throws Exception {
        Clinic clinic = createClinic("appt-series-partial-" + UUID.randomUUID(), "Partial Series Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Partial");
        var type = createAppointmentType(clinic.getId(), "Therapy / 30 min", 30, "40.00");
        for (int day = 0; day <= 6; day++) {
            createWorkingHours(clinic.getId(), provider.getId(), day, LocalTime.of(9, 0), LocalTime.of(17, 0));
        }
        var patient = createPatient(clinic.getId(), "Series", "Patient", "+15550001111");
        Instant firstStart = nextInstantAt(DayOfWeek.MONDAY, LocalTime.of(9, 0));

        // Pre-book the second occurrence's slot out from under the series with a guest booking.
        Instant secondOccurrenceStart = firstStart.plus(java.time.Duration.ofDays(7));
        var conflictingSlot = new com.clinicops.scheduling.Slot();
        conflictingSlot.setTenantId(clinic.getId());
        conflictingSlot.setProviderId(provider.getId());
        conflictingSlot.setAppointmentTypeId(type.getId());
        conflictingSlot.setStartTime(secondOccurrenceStart);
        conflictingSlot.setEndTime(secondOccurrenceStart.plusSeconds(1800));
        slotRepository.save(conflictingSlot);
        mockMvc.perform(post("/api/appointments/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateGuestAppointmentRequest(
                                conflictingSlot.getId(), provider.getId(), type.getId(),
                                "Blocker", "+15550009999", null, "idem-blocker"))))
                .andExpect(status().isOk());

        var request = new CreateAppointmentSeriesRequest(
                patient.getId(), provider.getId(), type.getId(), firstStart, 1, 3, "idem-series-partial");

        String firstAttempt = mockMvc.perform(post("/api/appointments/series")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created.length()").value(2))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].occurrenceIndex").value(1))
                .andReturn().getResponse().getContentAsString();

        // Retrying doesn't duplicate the two already-created occurrences.
        mockMvc.perform(post("/api/appointments/series")
                        .with(asFrontDesk("fd-1", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created.length()").value(2))
                .andExpect(jsonPath("$.conflicts.length()").value(1));

        String seriesId = objectMapper.readTree(firstAttempt).get("series").get("id").asText();
        assertThat(appointmentRepository.findAllBySeriesId(UUID.fromString(seriesId))).hasSize(2);
    }
}
