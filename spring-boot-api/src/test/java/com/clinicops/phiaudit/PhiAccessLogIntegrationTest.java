package com.clinicops.phiaudit;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.CreatePatientRequest;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
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

/**
 * Verifies the actual wiring PatientController/EncounterController call
 * into PhiAccessAuditService, and the clinic_admin-only review endpoint -
 * PhiAccessAuditServiceTest already covers the service's own logic in
 * isolation (pure Mockito), this is the "does it actually fire on a real
 * request, scoped to the right tenant" half.
 */
class PhiAccessLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PhiAccessLogRepository phiAccessLogRepository;

    @Test
    void creatingAndReadingAPatientEachWriteTheirOwnLogRow() throws Exception {
        Clinic clinic = createClinic("phi-patient-" + UUID.randomUUID(), "PHI Patient Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/patients").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePatientRequest("Audit", "Patient", null, "+15551230000", null, null, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID patientId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/patients/" + patientId).with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk());

        List<PhiAccessLog> rows = phiAccessLogRepository.findTop200ByTenantIdAndPatientIdOrderByCreatedAtDesc(clinic.getId(), patientId);
        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getAction()).isEqualTo("write");
            assertThat(r.getResourceType()).isEqualTo("patient");
            assertThat(r.getActorRole()).isEqualTo("front_desk");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getAction()).isEqualTo("read");
            assertThat(r.getActorRole()).isEqualTo("provider");
        });
    }

    @Test
    void anEncounterReadResolvesThePatientIdThroughTheAppointment() throws Exception {
        Clinic clinic = createClinic("phi-encounter-" + UUID.randomUUID(), "PHI Encounter Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Provider provider = createProviderLinkedToAppUser(clinic.getId(), "Dr. Audit", "prov-sub");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(clinic.getId(), "Encounter", "Patient", "+15550009999");
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        createEncounter(clinic.getId(), appointment.getId(), provider.getId());

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/encounter").with(asProvider("prov-sub", orgAlias)))
                .andExpect(status().isOk());

        List<PhiAccessLog> rows = phiAccessLogRepository.findTop200ByTenantIdAndPatientIdOrderByCreatedAtDesc(clinic.getId(), patient.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getResourceType()).isEqualTo("encounter");
        assertThat(rows.get(0).getAction()).isEqualTo("read");
    }

    @Test
    void theReviewEndpointIsClinicAdminOnlyAndTenantScoped() throws Exception {
        Clinic clinicA = createClinic("phi-review-a-" + UUID.randomUUID(), "Clinic A");
        Patient patientA = createPatient(clinicA.getId(), "A", "Patient", "+15550001111");
        mockMvc.perform(get("/api/patients/" + patientA.getId()).with(asClinicAdmin("admin-a", clinicA.getKeycloakOrgId())))
                .andExpect(status().isOk());

        Clinic clinicB = createClinic("phi-review-b-" + UUID.randomUUID(), "Clinic B");

        // front_desk/provider can't review the log even for their own clinic.
        mockMvc.perform(get("/api/clinic/phi-access-log").with(asFrontDesk("fd", clinicA.getKeycloakOrgId())))
                .andExpect(status().isForbidden());

        // clinic_admin sees their own clinic's row...
        mockMvc.perform(get("/api/clinic/phi-access-log").with(asClinicAdmin("admin-a", clinicA.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.patientId=='" + patientA.getId() + "')]").exists());

        // ...but clinic B's clinic_admin sees none of it.
        mockMvc.perform(get("/api/clinic/phi-access-log").with(asClinicAdmin("admin-b", clinicB.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.patientId=='" + patientA.getId() + "')]").doesNotExist());
    }
}
