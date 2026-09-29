package com.clinicops.inventory;

import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierControllerIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createListGetAndUpdateASupplierThenDeactivateAndReactivateIt() throws Exception {
        Clinic clinic = createClinic("supplier-crud-" + UUID.randomUUID(), "Supplier CRUD Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        String body = mockMvc.perform(post("/api/inventory/suppliers").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateSupplierRequest("MedSupply Co", "Jane Rep", "555-0100", "sales@medsupply.example"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(get("/api/inventory/suppliers").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(post("/api/inventory/suppliers/" + id + "/update").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSupplierRequest(null, null, null, null, "inactive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(post("/api/inventory/suppliers/" + id + "/update").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSupplierRequest(null, null, null, null, "active"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    void anInvalidStatusIsRejected() throws Exception {
        Clinic clinic = createClinic("supplier-badstatus-" + UUID.randomUUID(), "Bad Status Clinic");
        Supplier supplier = createSupplier(clinic.getId(), "Some Supplier");

        mockMvc.perform(post("/api/inventory/suppliers/" + supplier.getId() + "/update").with(asClinicAdmin("admin", clinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateSupplierRequest(null, null, null, null, "deleted"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyClinicAdminAndFrontDeskCanReachSuppliers() throws Exception {
        Clinic clinic = createClinic("supplier-role-" + UUID.randomUUID(), "Role Gate Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/inventory/suppliers").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/suppliers").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantSupplierIsNotFound() throws Exception {
        Clinic clinic = createClinic("supplier-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Clinic otherClinic = createClinic("supplier-tenant-b-" + UUID.randomUUID(), "Clinic B");
        Supplier supplier = createSupplier(clinic.getId(), "Isolated Supplier");

        mockMvc.perform(get("/api/inventory/suppliers/" + supplier.getId()).with(asClinicAdmin("admin", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
