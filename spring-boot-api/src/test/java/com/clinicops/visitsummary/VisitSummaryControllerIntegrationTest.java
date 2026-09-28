package com.clinicops.visitsummary;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.CreateAppointmentRequest;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisitSummaryControllerIntegrationTest extends AbstractIntegrationTest {

    private Appointment seedStaffAppointment(UUID tenantId) {
        Provider provider = createProvider(tenantId, "Dr. Summary");
        Patient patient = createPatient(tenantId, "Visit", "Summary", "+15550012345");
        AppointmentType type = createAppointmentType(tenantId, "Consult", 30, "50.00");
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));
        return createBookedAppointment(tenantId, slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
    }

    private void assertThatPdf(byte[] bytes) {
        assertThat(bytes).isNotEmpty();
        assertThat(java.util.Arrays.copyOf(bytes, 4)).isEqualTo(new byte[]{'%', 'P', 'D', 'F'});
    }

    @Test
    void staffGeneratesAVisitSummaryPdfForAllThreeRoles() throws Exception {
        Clinic clinic = createClinic("visit-summary-staff-" + UUID.randomUUID(), "Visit Summary Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Appointment appointment = seedStaffAppointment(clinic.getId());

        List<RequestPostProcessor> staffAuths = List.of(
                asProvider("prov", orgAlias), asFrontDesk("fd", orgAlias), asClinicAdmin("admin", orgAlias));
        for (RequestPostProcessor auth : staffAuths) {
            byte[] pdf = mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/visit-summary/pdf").with(auth))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThatPdf(pdf);
        }
    }

    @Test
    void staffCrossTenantAppointmentIs404() throws Exception {
        Clinic clinic = createClinic("visit-summary-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Appointment appointment = seedStaffAppointment(clinic.getId());
        Clinic otherClinic = createClinic("visit-summary-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/visit-summary/pdf")
                        .with(asFrontDesk("fd", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void patientGeneratesTheirOwnVisitSummaryButNotSomeoneElses() throws Exception {
        Clinic clinic = createClinic("visit-summary-patient-" + UUID.randomUUID(), "Patient Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProvider(clinic.getId(), "Dr. Own");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "80.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));

        // Book through the real patient_portal flow so customerUserId is a
        // genuinely provisioned AppUser id, not a hand-wired one - same
        // fixture pattern CancellationIntegrationTest's own self-cancel
        // test already established.
        String bookingBody = mockMvc.perform(post("/api/appointments")
                        .with(asPatient("visit-summary-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAppointmentRequest(
                                slot.getId(), provider.getId(), type.getId(), null, "idem-visit-summary-own"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID appointmentId = UUID.fromString(objectMapper.readTree(bookingBody).get("id").asText());

        byte[] pdf = mockMvc.perform(get("/api/my-appointments/" + appointmentId + "/visit-summary/pdf")
                        .with(asPatient("visit-summary-owner")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThatPdf(pdf);

        mockMvc.perform(get("/api/my-appointments/" + appointmentId + "/visit-summary/pdf")
                        .with(asPatient("visit-summary-someone-else")))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffOnlyEndpointIsForbiddenForAPatientToken() throws Exception {
        Clinic clinic = createClinic("visit-summary-forbidden-" + UUID.randomUUID(), "Forbidden Clinic");
        Appointment appointment = seedStaffAppointment(clinic.getId());

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/visit-summary/pdf")
                        .with(asPatient("someone")))
                .andExpect(status().isForbidden());
    }
}
