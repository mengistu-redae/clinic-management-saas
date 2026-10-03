package com.clinicops.insurance;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.invoice.Invoice;
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

class ClaimControllerIntegrationTest extends AbstractIntegrationTest {

    private record Fixture(Clinic clinic, Patient patient, Invoice invoice) {
    }

    private Fixture seedInvoice(String orgAlias, String clinicName, boolean withPatient) {
        Clinic clinic = createClinic(orgAlias, clinicName);
        Provider provider = createProvider(clinic.getId(), "Dr. Claim");
        AppointmentType type = createAppointmentType(clinic.getId(), "Visit", 30, "150.00");
        Patient patient = withPatient ? createPatient(clinic.getId(), "Claim", "Patient", "+15552220000") : null;
        Slot slot = createSlot(clinic.getId(), provider.getId(), type.getId(), Instant.now().plusSeconds(3600), Instant.now().plusSeconds(5400));
        Appointment appointment = createBookedAppointment(
                clinic.getId(), slot.getId(), withPatient ? patient.getId() : null, provider.getId(), type.getId(), null);
        Invoice invoice = createInvoiceForAppointment(clinic.getId(), appointment.getId(), new BigDecimal("150.00"));
        return new Fixture(clinic, patient, invoice);
    }

    @Test
    void fullLifecyclePaidAndClosed() throws Exception {
        Fixture fixture = seedInvoice("claim-lifecycle-" + UUID.randomUUID(), "Lifecycle Clinic", true);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), fixture.patient().getId(), "Acme Health");

        String body = mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), "Filed at checkout"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.billedAmount").value(150.00))
                .andReturn().getResponse().getContentAsString();
        UUID claimId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/claims/" + claimId + "/submit").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitClaimRequest("CLM-100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"))
                .andExpect(jsonPath("$.claimNumber").value("CLM-100"));

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordAdjudicationRequest("paid", new BigDecimal("120.00"), new BigDecimal("120.00"), BigDecimal.ZERO, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paid"))
                .andExpect(jsonPath("$.allowedAmount").value(120.00));

        mockMvc.perform(post("/api/claims/" + claimId + "/close").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CloseClaimRequest("Fully resolved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));

        mockMvc.perform(get("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/patients/" + fixture.patient().getId() + "/claims").with(asClinicAdmin("admin", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void deniedClaimCanBeAppealedAndReAdjudicated() throws Exception {
        Fixture fixture = seedInvoice("claim-denied-" + UUID.randomUUID(), "Denied Clinic", true);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), fixture.patient().getId(), "Acme Health");

        String body = mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), null))))
                .andReturn().getResponse().getContentAsString();
        UUID claimId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        mockMvc.perform(post("/api/claims/" + claimId + "/submit").with(asFrontDesk("fd", orgAlias))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new SubmitClaimRequest(null))));

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAdjudicationRequest("denied", null, null, null, "Not medically necessary"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("denied"));

        mockMvc.perform(post("/api/claims/" + claimId + "/appeal").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AppealClaimRequest("Attached supporting documentation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("appealed"));

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RecordAdjudicationRequest("partially_paid", new BigDecimal("60"), new BigDecimal("60"), new BigDecimal("40"), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("partially_paid"));
    }

    @Test
    void invalidAdjudicationOutcomeOrMissingRequiredFieldsIsRejected() throws Exception {
        Fixture fixture = seedInvoice("claim-invalid-" + UUID.randomUUID(), "Invalid Clinic", true);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), fixture.patient().getId(), "Acme Health");
        String body = mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), null))))
                .andReturn().getResponse().getContentAsString();
        UUID claimId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        mockMvc.perform(post("/api/claims/" + claimId + "/submit").with(asFrontDesk("fd", orgAlias))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new SubmitClaimRequest(null))));

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAdjudicationRequest("rejected", null, null, null, null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAdjudicationRequest("denied", null, null, null, null))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/claims/" + claimId + "/record-adjudication").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordAdjudicationRequest("paid", new BigDecimal("1"), null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingAClaimForAGuestInvoiceWithNoPatientIsRejected() throws Exception {
        Fixture fixture = seedInvoice("claim-guest-" + UUID.randomUUID(), "Guest Clinic", false);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        Patient unrelatedPatient = createPatient(fixture.clinic().getId(), "Unrelated", "Patient", "+15552220001");
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), unrelatedPatient.getId(), "Acme Health");

        mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void creatingAClaimWithAPolicyBelongingToADifferentPatientIsRejected() throws Exception {
        Fixture fixture = seedInvoice("claim-mismatch-" + UUID.randomUUID(), "Mismatch Clinic", true);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        Patient otherPatient = createPatient(fixture.clinic().getId(), "Other", "Patient", "+15552220002");
        InsurancePolicy othersPolicy = createInsurancePolicy(fixture.clinic().getId(), otherPatient.getId(), "Acme Health");

        mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(othersPolicy.getId(), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyFrontDeskAndClinicAdminCanAccessClaims() throws Exception {
        Fixture fixture = seedInvoice("claim-roles-" + UUID.randomUUID(), "Roles Clinic", true);
        String orgAlias = fixture.clinic().getKeycloakOrgId();
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), fixture.patient().getId(), "Acme Health");

        mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asProvider("prov", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asPatient("pat")))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantInvoiceAndClaimAreNotFound() throws Exception {
        Fixture fixture = seedInvoice("claim-tenant-a-" + UUID.randomUUID(), "Clinic A", true);
        Clinic otherClinic = createClinic("claim-tenant-b-" + UUID.randomUUID(), "Clinic B");
        InsurancePolicy policy = createInsurancePolicy(fixture.clinic().getId(), fixture.patient().getId(), "Acme Health");

        mockMvc.perform(post("/api/invoices/" + fixture.invoice().getId() + "/claims").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateClaimRequest(policy.getId(), null))))
                .andExpect(status().isNotFound());

        Claim claim = new Claim();
        claim.setTenantId(fixture.clinic().getId());
        claim.setInvoiceId(fixture.invoice().getId());
        claim.setInsurancePolicyId(policy.getId());
        claim.setPatientId(fixture.patient().getId());
        claim.setBilledAmount(new BigDecimal("150.00"));
        claim = claimRepository.save(claim);

        mockMvc.perform(post("/api/claims/" + claim.getId() + "/submit").with(asFrontDesk("fd", otherClinic.getKeycloakOrgId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SubmitClaimRequest(null))))
                .andExpect(status().isNotFound());
    }
}
