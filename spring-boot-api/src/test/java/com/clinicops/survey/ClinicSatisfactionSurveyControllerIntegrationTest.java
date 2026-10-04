package com.clinicops.survey;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClinicSatisfactionSurveyControllerIntegrationTest extends AbstractIntegrationTest {

    private Appointment seedCheckedOutAppointment(UUID tenantId, String firstName, String lastName) {
        Provider provider = createProvider(tenantId, "Dr. Summary");
        AppointmentType type = createAppointmentType(tenantId, "Visit", 30, "50.00");
        Patient patient = createPatient(tenantId, firstName, lastName, "+15550008888");
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(tenantId, slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
        appointment.setStatus("checked_out");
        appointmentRepository.save(appointment);
        SatisfactionSurvey survey = new SatisfactionSurvey();
        survey.setTenantId(tenantId);
        survey.setAppointmentId(appointment.getId());
        satisfactionSurveyRepository.save(survey);
        return appointment;
    }

    private void submit(Appointment appointment, int rating, String comment) {
        SatisfactionSurvey survey = satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointment.getId(), appointment.getTenantId()).orElseThrow();
        survey.setRating(rating);
        survey.setComment(comment);
        survey.setSubmittedAt(Instant.now());
        satisfactionSurveyRepository.save(survey);
    }

    @Test
    void summaryAveragesOnlySubmittedSurveysAndListsRecentComments() throws Exception {
        Clinic clinic = createClinic("survey-summary-" + UUID.randomUUID(), "Survey Summary Clinic");

        Appointment a = seedCheckedOutAppointment(clinic.getId(), "Alice", "A");
        Appointment b = seedCheckedOutAppointment(clinic.getId(), "Bob", "B");
        seedCheckedOutAppointment(clinic.getId(), "Carol", "C"); // still pending - excluded from the average

        submit(a, 5, "Loved it");
        submit(b, 3, "It was okay");

        mockMvc.perform(get("/api/clinic/satisfaction-surveys/summary").with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.averageRating").value(4.00))
                .andExpect(jsonPath("$.recentFeedback.length()").value(2))
                .andExpect(jsonPath("$.recentFeedback[0].patientName").value("Bob B"))
                .andExpect(jsonPath("$.recentFeedback[1].patientName").value("Alice A"));
    }

    @Test
    void onlyClinicAdminCanReadTheSummary() throws Exception {
        Clinic clinic = createClinic("survey-role-" + UUID.randomUUID(), "Survey Role Clinic");

        mockMvc.perform(get("/api/clinic/satisfaction-surveys/summary").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/clinic/satisfaction-surveys/summary").with(asProvider("prov", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }
}
