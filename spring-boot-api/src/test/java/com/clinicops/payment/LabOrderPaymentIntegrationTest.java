package com.clinicops.payment;

import com.clinicops.clinic.Clinic;
import com.clinicops.laborder.CreateLabOrderRequest;
import com.clinicops.laborder.TestItem;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LabOrderPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID createOrder(Clinic clinic, Provider provider, Patient patient) throws Exception {
        createLabTestRate(clinic.getId(), "CBC", "20.00", "0.00");
        String body = mockMvc.perform(post("/api/lab-orders").with(asProvider("prov", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateLabOrderRequest(
                                patient.getId(), null, provider.getId(), null, null,
                                List.of(new TestItem("CBC", "CBC", "blood", null)), false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("order").get("id").asText());
    }

    @Test
    void recordsAndListsALabOrderPayment() throws Exception {
        Clinic clinic = createClinic("pay-lab-" + UUID.randomUUID(), "Pay Lab Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Pay");
        Patient patient = createPatient(clinic.getId(), "Pay", "Patient", "+15551112222");
        UUID orderId = createOrder(clinic, provider, patient);
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/lab-orders/" + orderId + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new java.math.BigDecimal("20.00"), "cash", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(20.00))
                .andExpect(jsonPath("$.method").value("cash"));

        mockMvc.perform(get("/api/lab-orders/" + orderId + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void crossTenantLabOrderPaymentsAreNotFound() throws Exception {
        Clinic clinic = createClinic("pay-lab-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Provider provider = createProvider(clinic.getId(), "Dr. A");
        Patient patient = createPatient(clinic.getId(), "A", "Patient", "+15550000001");
        UUID orderId = createOrder(clinic, provider, patient);

        Clinic otherClinic = createClinic("pay-lab-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/lab-orders/" + orderId + "/payments").with(asProvider("prov", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/lab-orders/" + orderId + "/payments").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new java.math.BigDecimal("10.00"), "cash", null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void appointmentAndLabOrderPaymentsAreScopedPerOwner() throws Exception {
        Clinic clinic = createClinic("pay-scope-" + UUID.randomUUID(), "Scope Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Scope");
        Patient patient = createPatient(clinic.getId(), "Scope", "Patient", "+15550000002");
        UUID labOrderId = createOrder(clinic, provider, patient);
        String orgAlias = clinic.getKeycloakOrgId();

        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        mockMvc.perform(post("/api/lab-orders/" + labOrderId + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new java.math.BigDecimal("20.00"), "cash", null, null))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new java.math.BigDecimal("50.00"), "card", "txn-1", null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/lab-orders/" + labOrderId + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].method").value("cash"));

        mockMvc.perform(get("/api/appointments/" + appointment.getId() + "/payments").with(asProvider("prov", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].method").value("card"));
    }

    @Test
    void exactlyOneOwnerCheckConstraintRejectsBothNullAndBothSet() throws Exception {
        Clinic clinic = createClinic("pay-check-" + UUID.randomUUID(), "Check Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Check");
        Patient patient = createPatient(clinic.getId(), "Check", "Patient", "+15550000003");
        UUID labOrderId = createOrder(clinic, provider, patient);

        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        var slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().minusSeconds(1800));
        var appointment = createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, tenant_id, appointment_id, lab_order_id, amount, method, created_at) "
                        + "VALUES (gen_random_uuid(), ?, NULL, NULL, 10.00, 'cash', now())",
                clinic.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, tenant_id, appointment_id, lab_order_id, amount, method, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 10.00, 'cash', now())",
                clinic.getId(), appointment.getId(), labOrderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
