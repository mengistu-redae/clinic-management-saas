package com.clinicops.accounting;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void listingSeedsTheStarterAccountsOnFirstAccess() throws Exception {
        Clinic clinic = createClinic("acct-seed-" + UUID.randomUUID(), "Seed Clinic");

        mockMvc.perform(get("/api/clinic/accounts").with(asAccountant("acct", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.code=='1000')].name").value("Cash"))
                .andExpect(jsonPath("$[?(@.code=='4000')].name").value("Service Revenue"))
                .andExpect(jsonPath("$[?(@.code=='4900')].name").value("Refunds & Allowances"));
    }

    @Test
    void createListGetAndUpdateAnAccount() throws Exception {
        Clinic clinic = createClinic("acct-crud-" + UUID.randomUUID(), "CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/clinic/accounts").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest("2000", "Accounts Payable", "liability"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/accounts/" + id).with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Accounts Payable"));

        mockMvc.perform(post("/api/clinic/accounts/" + id + "/update").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAccountRequest("Trade Payables", "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trade Payables"))
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(get("/api/clinic/accounts").with(asClinicAdmin("admin", orgAlias)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void anInvalidTypeOrStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("acct-invalid-" + UUID.randomUUID(), "Invalid Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/accounts").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest("9000", "Nonsense", "not-a-type"))))
                .andExpect(status().isBadRequest());

        Account account = createAccount(clinic.getId(), "5000", "Cost of Goods", "expense");
        mockMvc.perform(post("/api/clinic/accounts/" + account.getId() + "/update").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateAccountRequest(null, "deleted"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateCodeIsRejected() throws Exception {
        Clinic clinic = createClinic("acct-dupe-" + UUID.randomUUID(), "Duplicate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(post("/api/clinic/accounts").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest("1000", "Petty Cash", "asset"))))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAccountantAndClinicAdminCanReachThisController() throws Exception {
        Clinic clinic = createClinic("acct-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/clinic/accounts").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/clinic/accounts").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/clinic/accounts").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest("2100", "Tax Payable", "liability"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantAccountIsNotFound() throws Exception {
        Clinic clinic = createClinic("acct-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Account account = createAccount(clinic.getId(), "1500", "Isolated Account", "asset");
        Clinic otherClinic = createClinic("acct-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/clinic/accounts/" + account.getId()).with(asAccountant("acct", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
