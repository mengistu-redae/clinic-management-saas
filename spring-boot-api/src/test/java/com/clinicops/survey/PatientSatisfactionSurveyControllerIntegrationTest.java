package com.clinicops.survey;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import com.clinicops.user.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientSatisfactionSurveyControllerIntegrationTest extends AbstractIntegrationTest {

    private Appointment seedCheckedOutAppointmentOwnedByPortalUser(String orgAlias, String patientSubject) {
        Clinic clinic = createClinic(orgAlias, "Survey Patient Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Survey");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        AppUser appUser = createAppUser(patientSubject);
        Patient patient = createPatient(clinic.getId(), "Portal", "Patient", "+15550009999");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), appUser.getId());
        appointment.setStatus("checked_out");
        appointmentRepository.save(appointment);
        satisfactionSurveyRepository.save(pendingSurveyFor(appointment));
        return appointment;
    }

    private SatisfactionSurvey pendingSurveyFor(Appointment appointment) {
        SatisfactionSurvey survey = new SatisfactionSurvey();
        survey.setTenantId(appointment.getTenantId());
        survey.setAppointmentId(appointment.getId());
        return survey;
    }

    @Test
    void aPendingSurveyIsReadableAndSubmittableExactlyOnce() throws Exception {
        Appointment appointment = seedCheckedOutAppointmentOwnedByPortalUser("survey-patient-1-" + UUID.randomUUID(), "patient-survey-1");

        mockMvc.perform(get("/api/my-appointments/" + appointment.getId() + "/survey").with(asPatient("patient-survey-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedAt").doesNotExist());

        mockMvc.perform(post("/api/my-appointments/" + appointment.getId() + "/survey").with(asPatient("patient-survey-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitSatisfactionSurveyRequest(5, "Great visit"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.comment").value("Great visit"));

        mockMvc.perform(post("/api/my-appointments/" + appointment.getId() + "/survey").with(asPatient("patient-survey-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitSatisfactionSurveyRequest(1, "changed my mind"))))
                .andExpect(status().isConflict());
    }

    @Test
    void aNonOwnedAppointmentsSurveyIs404() throws Exception {
        Appointment appointment = seedCheckedOutAppointmentOwnedByPortalUser("survey-patient-2-" + UUID.randomUUID(), "patient-survey-owner");

        mockMvc.perform(get("/api/my-appointments/" + appointment.getId() + "/survey").with(asPatient("patient-survey-not-owner")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidRatingIsRejected() throws Exception {
        Appointment appointment = seedCheckedOutAppointmentOwnedByPortalUser("survey-patient-3-" + UUID.randomUUID(), "patient-survey-3");

        mockMvc.perform(post("/api/my-appointments/" + appointment.getId() + "/survey").with(asPatient("patient-survey-3"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitSatisfactionSurveyRequest(6, "too high"))))
                .andExpect(status().isBadRequest());
    }
}
