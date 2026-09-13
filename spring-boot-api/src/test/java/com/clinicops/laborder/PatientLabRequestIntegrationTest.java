package com.clinicops.laborder;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import com.clinicops.user.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientLabRequestIntegrationTest extends AbstractIntegrationTest {

    private AppUser findOrCreateAppUser(String subject) {
        return appUserRepository.findByKeycloakUserId(subject).orElseGet(() -> {
            AppUser fresh = new AppUser();
            fresh.setKeycloakUserId(subject);
            fresh.setEmail(subject + "@example.test");
            fresh.setDisplayName(subject);
            return appUserRepository.save(fresh);
        });
    }

    @Test
    void requestToConfirmAndOrderRoundTripReprices() throws Exception {
        Clinic clinic = createClinic("labreq-round-" + UUID.randomUUID(), "Round Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Round");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "5.00");

        String reqBody = mockMvc.perform(post("/api/my-lab-orders").with(asPatient("patient-round"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(
                                clinic.getId(), List.of("Complete Blood Count"), "routine follow-up"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("requested"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(reqBody).get("order").get("id").asText());

        mockMvc.perform(get("/api/lab-orders/requests").with(asClinicAdmin("admin", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()));

        mockMvc.perform(post("/api/lab-orders/" + id + "/confirm-and-order").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmAndOrderRequest(
                                provider.getId(), null,
                                List.of(new TestItem("CBC", "Complete Blood Count", "blood", null)), false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("ordered"))
                .andExpect(jsonPath("$.order.totalCost").value(25.00))
                .andExpect(jsonPath("$.tests[0].testCode").value("CBC"));

        mockMvc.perform(get("/api/my-lab-orders").with(asPatient("patient-round")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].order.status").value("ordered"));
    }

    @Test
    void confirmAndOrderWithoutTestCodesFailsUntilAssigned() throws Exception {
        Clinic clinic = createClinic("labreq-noassign-" + UUID.randomUUID(), "No Assign Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. NoAssign");

        String reqBody = mockMvc.perform(post("/api/my-lab-orders").with(asPatient("patient-noassign"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(
                                clinic.getId(), List.of("Something Unassigned"), null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(reqBody).get("order").get("id").asText());

        // tests=null leaves the freeform-name row's testCode blank - pricing can't proceed until one is assigned.
        mockMvc.perform(post("/api/lab-orders/" + id + "/confirm-and-order").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmAndOrderRequest(provider.getId(), null, null, false))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAPatientCanCreateARequest() throws Exception {
        Clinic clinic = createClinic("labreq-role-" + UUID.randomUUID(), "Role Clinic");

        mockMvc.perform(post("/api/my-lab-orders").with(asProvider("prov", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(clinic.getId(), List.of("CBC"), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmingAnAlreadyIssuedRequestIsRejected() throws Exception {
        Clinic clinic = createClinic("labreq-idempotent-" + UUID.randomUUID(), "Idempotent Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Idem");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "0.00");

        String reqBody = mockMvc.perform(post("/api/my-lab-orders").with(asPatient("patient-idem"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(clinic.getId(), List.of("CBC"), null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(reqBody).get("order").get("id").asText());

        ConfirmAndOrderRequest confirm = new ConfirmAndOrderRequest(
                provider.getId(), null, List.of(new TestItem("CBC", "CBC", "blood", null)), false);

        mockMvc.perform(post("/api/lab-orders/" + id + "/confirm-and-order").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirm)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/lab-orders/" + id + "/confirm-and-order").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirm)))
                .andExpect(status().isConflict());
    }

    @Test
    void aPatientsRequestIsInvisibleToAnotherPatient() throws Exception {
        Clinic clinic = createClinic("labreq-crosspatient-" + UUID.randomUUID(), "Cross Patient Clinic");

        mockMvc.perform(post("/api/my-lab-orders").with(asPatient("patient-cross-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(clinic.getId(), List.of("CBC"), null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my-lab-orders").with(asPatient("patient-cross-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void myLabOrdersUnionsRequestedAndEncounterOwnershipPaths() throws Exception {
        Clinic clinic = createClinic("labreq-union-" + UUID.randomUUID(), "Union Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Union");
        Patient patient = createPatient(clinic.getId(), "Union", "Patient", "+15557778888");
        createLabTestRate(clinic.getId(), "CBC", "20.00", "0.00");

        AppUser appUser = findOrCreateAppUser("patient-union");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), appUser.getId());
        Encounter encounter = createEncounter(clinic.getId(), appointment.getId(), provider.getId());

        // Order A: staff-created, linked via the encounter the patient owns through their appointment.
        String orderABody = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), encounter.getId(), provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderAId = UUID.fromString(objectMapper.readTree(orderABody).get("order").get("id").asText());

        // Order B: the same patient's own directly-requested order.
        String orderBBody = mockMvc.perform(post("/api/my-lab-orders").with(asPatient("patient-union"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabRequestRequest(clinic.getId(), List.of("Lipid Panel"), null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID orderBId = UUID.fromString(objectMapper.readTree(orderBBody).get("order").get("id").asText());

        String listBody = mockMvc.perform(get("/api/my-lab-orders").with(asPatient("patient-union")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        List<String> ids = objectMapper.readTree(listBody).findValuesAsText("id");
        org.assertj.core.api.Assertions.assertThat(ids).contains(orderAId.toString(), orderBId.toString());
    }
}
