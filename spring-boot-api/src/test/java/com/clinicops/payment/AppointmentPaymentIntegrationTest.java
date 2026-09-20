package com.clinicops.payment;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
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

/**
 * AppointmentPaymentController had zero dedicated test coverage before this
 * - LabOrderPaymentIntegrationTest only exercises this controller's happy
 * path incidentally (appointmentAndLabOrderPaymentsAreScopedPerOwner), never
 * its own cross-tenant/role/validation edges. Same shape as that file's own
 * tests for the lab-order side, applied here to the appointment side.
 */
class AppointmentPaymentIntegrationTest extends AbstractIntegrationTest {

    private Appointment bookAppointment(Clinic clinic, Provider provider) {
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(clinic.getId(), "Pay", "Patient", "+15550001234");
        return createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
    }

    @Test
    void recordsAndListsAnAppointmentPayment() throws Exception {
        Clinic clinic = createClinic("pay-appt-" + UUID.randomUUID(), "Pay Appointment Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Pay");
        Appointment appointment = bookAppointment(clinic, provider);
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("50.00"), "card", "txn-99"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(50.00))
                .andExpect(jsonPath("$.method").value("card"));

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanRecordAPayment() throws Exception {
        Clinic clinic = createClinic("pay-appt-roles-" + UUID.randomUUID(), "Roles Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Roles");
        Appointment appointment = bookAppointment(clinic, provider);
        String orgAlias = clinic.getKeycloakOrgId();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("10.00"), "cash", null);

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/payments").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNonPositiveAmountOrBlankMethodIsRejected() throws Exception {
        Clinic clinic = createClinic("pay-appt-validation-" + UUID.randomUUID(), "Validation Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Validate");
        Appointment appointment = bookAppointment(clinic, provider);
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(BigDecimal.ZERO, "cash", null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("10.00"), "", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossTenantAppointmentPaymentsAreNotFound() throws Exception {
        Clinic clinic = createClinic("pay-appt-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Provider provider = createProvider(clinic.getId(), "Dr. A");
        Appointment appointment = bookAppointment(clinic, provider);

        Clinic otherClinic = createClinic("pay-appt-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/payments").with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("10.00"), "cash", null))))
                .andExpect(status().isNotFound());
    }
}
