package com.clinicops.accounting;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.patient.Patient;
import com.clinicops.payment.CreatePaymentRequest;
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

/** Confirms the real end-to-end wiring - a payment recorded through the actual AppointmentPaymentController auto-posts a balanced journal entry, reflected in both the raw journal listing and the trial balance. */
class JournalControllerIntegrationTest extends AbstractIntegrationTest {

    private Appointment bookAppointment(Clinic clinic, Provider provider) {
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Patient patient = createPatient(clinic.getId(), "Ledger", "Patient", "+15550009999");
        return createBookedAppointment(clinic.getId(), slot.getId(), patient.getId(), provider.getId(), type.getId(), null);
    }

    @Test
    void recordingAPaymentAutoPostsABalancedJournalEntryReflectedInTheTrialBalance() throws Exception {
        Clinic clinic = createClinic("journal-e2e-" + UUID.randomUUID(), "Journal E2E Clinic");
        Provider provider = createProvider(clinic.getId(), "Dr. Ledger");
        Appointment appointment = bookAppointment(clinic, provider);
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/appointments/" + appointment.getId() + "/payments").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePaymentRequest(new BigDecimal("50.00"), "card", "txn-ledger", null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/clinic/journal-entries").with(asAccountant("acct", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].entry.sourceType").value("appointment_payment"))
                .andExpect(jsonPath("$[0].lines.length()").value(2))
                .andExpect(jsonPath("$[0].lines[0].amount").value(50.00))
                .andExpect(jsonPath("$[0].lines[1].amount").value(50.00));

        mockMvc.perform(get("/api/clinic/trial-balance").with(asAccountant("acct", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='1000')].balance").value(50.00))
                .andExpect(jsonPath("$[?(@.code=='4000')].balance").value(50.00));
    }

    @Test
    void onlyAccountantAndClinicAdminCanReadTheJournalOrTrialBalance() throws Exception {
        Clinic clinic = createClinic("journal-role-" + UUID.randomUUID(), "Journal Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/journal-entries").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/clinic/trial-balance").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void journalEntriesAreScopedPerTenant() throws Exception {
        Clinic clinicA = createClinic("journal-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Account cashA = createAccount(clinicA.getId(), "1000", "Cash", "asset");
        Account revenueA = createAccount(clinicA.getId(), "4000", "Service Revenue", "revenue");
        JournalEntry entry = createJournalEntry(clinicA.getId(), "Test posting", "appointment_payment", UUID.randomUUID());
        createJournalLine(clinicA.getId(), entry.getId(), cashA.getId(), "debit", new BigDecimal("15.00"));
        createJournalLine(clinicA.getId(), entry.getId(), revenueA.getId(), "credit", new BigDecimal("15.00"));

        Clinic clinicB = createClinic("journal-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/clinic/journal-entries").with(asAccountant("acct", clinicB.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
