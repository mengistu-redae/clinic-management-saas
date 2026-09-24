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
 * Owner-agnostic refund endpoints (phase 16) - PaymentController addresses
 * a payment directly by its own id, unlike AppointmentPaymentController/
 * LabOrderPaymentController which are nested under their owner.
 */
class PaymentControllerIntegrationTest extends AbstractIntegrationTest {

    private UUID recordCardPayment(Clinic clinic, String amount) throws Exception {
        Provider provider = createProvider(clinic.getId(), "Dr. Refund");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, amount);
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(clinic.getId(), "Refund", "Patient", "+15550002222");
        Appointment appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        String body = mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal(amount), "card", null, null))))
                .andExpect(status().isOk())
                // Every payment recorded this way is now routed through the (mock) gateway - phase 16.
                .andExpect(jsonPath("$.gatewayTransactionId").isNotEmpty())
                .andExpect(jsonPath("$.gatewayStatus").value("succeeded"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test
    void aFullRefundMarksThePaymentRefunded() throws Exception {
        Clinic clinic = createClinic("refund-full-" + UUID.randomUUID(), "Refund Full Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID paymentId = recordCardPayment(clinic, "50.00");

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("50.00"), "patient no-show"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(50.00))
                .andExpect(jsonPath("$.gatewayRefundTransactionId").isNotEmpty());

        mockMvc.perform(get("/api/payments/" + paymentId + "/refunds").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void twoPartialRefundsThatReachTheFullAmountAreBothAccepted() throws Exception {
        Clinic clinic = createClinic("refund-partial-" + UUID.randomUUID(), "Refund Partial Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID paymentId = recordCardPayment(clinic, "100.00");

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("40.00"), null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("60.00"), null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/payments/" + paymentId + "/refunds").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aRefundExceedingTheRemainingAmountIs400() throws Exception {
        Clinic clinic = createClinic("refund-exceeds-" + UUID.randomUUID(), "Refund Exceeds Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID paymentId = recordCardPayment(clinic, "30.00");

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("30.01"), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanIssueARefund() throws Exception {
        Clinic clinic = createClinic("refund-roles-" + UUID.randomUUID(), "Refund Roles Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        UUID paymentId = recordCardPayment(clinic, "20.00");

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("5.00"), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPaymentRefundIs404() throws Exception {
        Clinic clinic = createClinic("refund-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("refund-tenant-b-" + UUID.randomUUID(), "Clinic B");
        UUID paymentId = recordCardPayment(clinic, "20.00");

        mockMvc.perform(post("/api/payments/" + paymentId + "/refund").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefundRequest(new BigDecimal("5.00"), null))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/payments/" + paymentId + "/refunds").with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
