package com.clinicops.finance;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import com.clinicops.user.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirms the real end-to-end wiring - running payroll through the actual controller posts real journal entries via JournalService, reflected in the trial balance. */
class PayrollControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void runningPayrollPostsBalancedEntriesPerEmployeeReflectedInTheTrialBalance() throws Exception {
        Clinic clinic = createClinic("payroll-e2e-" + UUID.randomUUID(), "Payroll E2E Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        AppUser staffA = createAppUser("payroll-e2e-a");
        AppUser staffB = createAppUser("payroll-e2e-b");
        createEmployee(clinic.getId(), staffA.getId(), staffA.getEmail(), new BigDecimal("1000.00"));
        createEmployee(clinic.getId(), staffB.getId(), staffB.getEmail(), new BigDecimal("500.00"));

        String body = mockMvc.perform(post("/api/clinic/payroll-runs").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RunPayrollRequest(2026, 9))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.totalAmount").value(1500.00))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(objectMapper.readTree(body).get("run").get("id").asText());

        mockMvc.perform(get("/api/clinic/payroll-runs/" + runId).with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()").value(2));

        mockMvc.perform(get("/api/clinic/trial-balance").with(asAccountant("acct", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='5000')].balance").value(1500.00))
                .andExpect(jsonPath("$[?(@.code=='1000')].balance").value(-1500.00));
    }

    @Test
    void runningPayrollTwiceForTheSameMonthIsRejected() throws Exception {
        Clinic clinic = createClinic("payroll-dupe-" + UUID.randomUUID(), "Duplicate Run Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        AppUser staff = createAppUser("payroll-dupe");
        createEmployee(clinic.getId(), staff.getId(), staff.getEmail(), new BigDecimal("800.00"));

        mockMvc.perform(post("/api/clinic/payroll-runs").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RunPayrollRequest(2026, 10))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/clinic/payroll-runs").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RunPayrollRequest(2026, 10))))
                .andExpect(status().isConflict());
    }

    @Test
    void runningPayrollWithNoActiveEmployeesIs400() throws Exception {
        Clinic clinic = createClinic("payroll-empty-" + UUID.randomUUID(), "Empty Clinic");
        mockMvc.perform(post("/api/clinic/payroll-runs").with(asAccountant("acct", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RunPayrollRequest(2026, 9))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAccountantAndClinicAdminCanRunOrReadPayroll() throws Exception {
        Clinic clinic = createClinic("payroll-role-" + UUID.randomUUID(), "Role Gate Clinic");
        mockMvc.perform(get("/api/clinic/payroll-runs").with(asProvider("prov", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/clinic/payroll-runs").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RunPayrollRequest(2026, 9))))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantPayrollRunIsNotFound() throws Exception {
        Clinic clinic = createClinic("payroll-tenant-a-" + UUID.randomUUID(), "Clinic A");
        PayrollRun run = createPayrollRun(clinic.getId(), 2026, 9, new BigDecimal("500.00"));
        Clinic otherClinic = createClinic("payroll-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/clinic/payroll-runs/" + run.getId()).with(asAccountant("acct", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
