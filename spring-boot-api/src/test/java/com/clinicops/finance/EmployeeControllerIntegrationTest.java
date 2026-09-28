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

class EmployeeControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createsAnEmployeeByEmailSnapshottingNameAndEmailFromTheRealAppUser() throws Exception {
        Clinic clinic = createClinic("emp-crud-" + UUID.randomUUID(), "Employee CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        AppUser staff = createAppUser("payee-1");

        String body = mockMvc.perform(post("/api/clinic/employees").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateEmployeeRequest(staff.getEmail(), new BigDecimal("2000.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(staff.getEmail()))
                .andExpect(jsonPath("$.fullName").value(staff.getDisplayName()))
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/clinic/employees/" + id).with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryAmount").value(2000.00));
    }

    @Test
    void anUnknownEmailIs404() throws Exception {
        Clinic clinic = createClinic("emp-unknown-" + UUID.randomUUID(), "Unknown Email Clinic");
        mockMvc.perform(post("/api/clinic/employees").with(asAccountant("acct", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateEmployeeRequest("nobody@example.test", new BigDecimal("1000.00")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSameAppUserCannotBeAddedToPayrollTwice() throws Exception {
        Clinic clinic = createClinic("emp-dupe-" + UUID.randomUUID(), "Duplicate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        AppUser staff = createAppUser("payee-dupe");
        createEmployee(clinic.getId(), staff.getId(), staff.getEmail(), new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/clinic/employees").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateEmployeeRequest(staff.getEmail(), new BigDecimal("1500.00")))))
                .andExpect(status().isConflict());
    }

    @Test
    void updateChangesSalaryAndStatusAndAnInvalidStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("emp-update-" + UUID.randomUUID(), "Update Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        AppUser staff = createAppUser("payee-update");
        Employee employee = createEmployee(clinic.getId(), staff.getId(), staff.getEmail(), new BigDecimal("1000.00"));

        mockMvc.perform(post("/api/clinic/employees/" + employee.getId() + "/update").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateEmployeeRequest(new BigDecimal("1200.00"), "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salaryAmount").value(1200.00))
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(post("/api/clinic/employees/" + employee.getId() + "/update").with(asAccountant("acct", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateEmployeeRequest(null, "retired"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAccountantAndClinicAdminCanReachThisController() throws Exception {
        Clinic clinic = createClinic("emp-role-" + UUID.randomUUID(), "Role Gate Clinic");
        mockMvc.perform(get("/api/clinic/employees").with(asFrontDesk("fd", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/clinic/employees").with(asProvider("prov", clinic.getKeycloakOrgId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantEmployeeIsNotFound() throws Exception {
        Clinic clinic = createClinic("emp-tenant-a-" + UUID.randomUUID(), "Clinic A");
        AppUser staff = createAppUser("payee-cross");
        Employee employee = createEmployee(clinic.getId(), staff.getId(), staff.getEmail(), new BigDecimal("1000.00"));
        Clinic otherClinic = createClinic("emp-tenant-b-" + UUID.randomUUID(), "Clinic B");

        mockMvc.perform(get("/api/clinic/employees/" + employee.getId()).with(asAccountant("acct", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
