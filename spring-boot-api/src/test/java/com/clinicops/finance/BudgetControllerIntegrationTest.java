package com.clinicops.finance;

import com.clinicops.accounting.Account;
import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListUpdateAndDeleteABudget() throws Exception {
        Clinic clinic = createClinic("budget-crud-" + UUID.randomUUID(), "Budget CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Account revenue = createAccount(clinic.getId(), "4000", "Service Revenue", "revenue");

        String body = mockMvc.perform(post("/api/clinic/budgets").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBudgetRequest(revenue.getId(), 2026, 9, new BigDecimal("5000.00")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/budgets").with(asClinicAdmin("admin", orgAlias)).param("year", "2026").param("month", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/clinic/budgets/" + id + "/update").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateBudgetRequest(new BigDecimal("6000.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(6000.00));

        mockMvc.perform(post("/api/clinic/budgets/" + id + "/delete").with(asAccountant("acct", orgAlias)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/clinic/budgets/" + id).with(asAccountant("acct", orgAlias)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDuplicateAccountAndPeriodIsRejected() throws Exception {
        Clinic clinic = createClinic("budget-dupe-" + UUID.randomUUID(), "Duplicate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Account revenue = createAccount(clinic.getId(), "4000", "Service Revenue", "revenue");
        createBudget(clinic.getId(), revenue.getId(), 2026, 9, new BigDecimal("5000.00"));

        mockMvc.perform(post("/api/clinic/budgets").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBudgetRequest(revenue.getId(), 2026, 9, new BigDecimal("7000.00")))))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownAccountIs404() throws Exception {
        Clinic clinic = createClinic("budget-unknown-" + UUID.randomUUID(), "Unknown Account Clinic");
        mockMvc.perform(post("/api/clinic/budgets").with(asAccountant("acct", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateBudgetRequest(UUID.randomUUID(), 2026, 9, new BigDecimal("5000.00")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAccountantAndClinicAdminCanReachThisController() throws Exception {
        Clinic clinic = createClinic("budget-role-" + UUID.randomUUID(), "Role Gate Clinic");
        mockMvc.perform(get("/api/clinic/budgets").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantBudgetIsNotFound() throws Exception {
        Clinic clinic = createClinic("budget-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Account revenue = createAccount(clinic.getId(), "4000", "Service Revenue", "revenue");
        Budget budget = createBudget(clinic.getId(), revenue.getId(), 2026, 9, new BigDecimal("5000.00"));
        Clinic otherClinic = createClinic("budget-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/clinic/budgets/" + budget.getId()).with(asAccountant("acct", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
