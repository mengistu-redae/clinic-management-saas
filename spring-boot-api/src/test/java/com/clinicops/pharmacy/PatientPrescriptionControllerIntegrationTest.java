package com.clinicops.pharmacy;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.clinic.Clinic;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.Prescription;
import com.clinicops.inventory.StockBatch;
import com.clinicops.patient.Patient;
import com.clinicops.provider.Provider;
import com.clinicops.scheduling.Slot;
import com.clinicops.support.AbstractIntegrationTest;
import com.clinicops.user.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientPrescriptionControllerIntegrationTest extends AbstractIntegrationTest {

    private Prescription seedOwnedPrescription(UUID tenantId, String patientSubject, String medicationName, String status) {
        AppUser appUser = createAppUser(patientSubject);
        Patient patient = createPatient(tenantId, "Portal", "Patient", "+15550001111");
        Provider provider = createProvider(tenantId, "Dr. Refill");
        AppointmentType type = createAppointmentType(tenantId, "Visit", 30, "50.00");
        Slot slot = createSlot(tenantId, provider.getId(), type.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        Appointment appointment = createBookedAppointment(tenantId, slot.getId(), patient.getId(), provider.getId(), type.getId(), appUser.getId());
        Encounter encounter = createEncounter(tenantId, appointment.getId(), provider.getId());
        Prescription prescription = createPrescription(tenantId, encounter.getId(), medicationName, null);
        if (status != null) {
            prescription.setStatus(status);
            prescriptionRepository.save(prescription);
        }
        return prescription;
    }

    @Test
    void myPrescriptionsOnlyShowsPrescriptionsReachableThroughTheCallersOwnAppointments() throws Exception {
        Clinic clinic = createClinic("myrx-" + UUID.randomUUID(), "My Rx Clinic");
        Prescription mine = seedOwnedPrescription(clinic.getId(), "patient-mine", "Amoxicillin", null);
        seedOwnedPrescription(clinic.getId(), "patient-other", "Penicillin V", null);

        mockMvc.perform(get("/api/my-prescriptions").with(asPatient("patient-mine")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(mine.getId().toString()))
                .andExpect(jsonPath("$[0].medicationName").value("Amoxicillin"))
                .andExpect(jsonPath("$[0].quantityAlreadyDispensed").value(0));
    }

    @Test
    void quantityAlreadyDispensedReflectsARealPriorDispense() throws Exception {
        Clinic clinic = createClinic("myrx-dispensed-" + UUID.randomUUID(), "Dispensed Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-dispensed", "Amoxicillin", null);
        Medication medication = createMedication(clinic.getId(), "Amoxicillin");
        StockBatch batch = createStockBatch(clinic.getId(), medication.getId(), 100);

        mockMvc.perform(post("/api/prescriptions/" + prescription.getId() + "/dispense").with(asPharmacist("pharm", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DispenseRequest(medication.getId(), batch.getId(), 7, null, false))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my-prescriptions").with(asPatient("patient-dispensed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quantityAlreadyDispensed").value(7));
    }

    @Test
    void creatingARefillRequestSucceedsForAnOwnedActivePrescription() throws Exception {
        Clinic clinic = createClinic("refill-create-" + UUID.randomUUID(), "Refill Create Clinic");
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-refill", "Amoxicillin", null);

        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-refill"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest("running low"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.notes").value("running low"));
    }

    @Test
    void aNonOwnedPrescriptionIs404() throws Exception {
        Clinic clinic = createClinic("refill-notowned-" + UUID.randomUUID(), "Not Owned Clinic");
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-owner", "Amoxicillin", null);

        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-not-owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonActivePrescriptionIsRejected() throws Exception {
        Clinic clinic = createClinic("refill-inactive-" + UUID.randomUUID(), "Inactive Clinic");
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-inactive", "Amoxicillin", "completed");

        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-inactive"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicatePendingRequestConflicts() throws Exception {
        Clinic clinic = createClinic("refill-dup-" + UUID.randomUUID(), "Dup Clinic");
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-dup", "Amoxicillin", null);

        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-dup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-dup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isConflict());
    }

    @Test
    void myRefillRequestsListsOnlyTheCallersOwn() throws Exception {
        Clinic clinic = createClinic("refill-mine-" + UUID.randomUUID(), "Mine Clinic");
        Prescription mine = seedOwnedPrescription(clinic.getId(), "patient-mine-refill", "Amoxicillin", null);
        seedOwnedPrescription(clinic.getId(), "patient-other-refill", "Penicillin V", null);

        mockMvc.perform(post("/api/my-prescriptions/" + mine.getId() + "/refill-requests").with(asPatient("patient-mine-refill"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/my-refill-requests").with(asPatient("patient-mine-refill")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/my-refill-requests").with(asPatient("patient-other-refill")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void approvingFlipsStatusAndWritesARealNotificationRow() throws Exception {
        Clinic clinic = createClinic("refill-approve-" + UUID.randomUUID(), "Approve Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-approve", "Amoxicillin", null);
        // Give the owning patient a real email so the notification actually gets written.
        Appointment appointment = appointmentRepository.findAllByCustomerUserId(
                appUserRepository.findByKeycloakUserId("patient-approve").orElseThrow().getId()).get(0);
        Patient patient = patientRepository.findById(appointment.getPatientId()).orElseThrow();
        patient.setEmail("patient-approve@example.test");
        patientRepository.save(patient);

        String body = mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andReturn().getResponse().getContentAsString();
        UUID refillId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/approve").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        org.assertj.core.api.Assertions.assertThat(
                notificationRepository.findAll().stream().anyMatch(n -> "refill_ready".equals(n.getType()) && n.getTenantId().equals(clinic.getId())))
                .isTrue();

        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/approve").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isConflict());
    }

    @Test
    void denyingFlipsStatusWithReviewNotesAndWritesNoNotification() throws Exception {
        Clinic clinic = createClinic("refill-deny-" + UUID.randomUUID(), "Deny Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-deny", "Amoxicillin", null);

        String body = mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-deny"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andReturn().getResponse().getContentAsString();
        UUID refillId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
        long notificationCountBefore = notificationRepository.count();

        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/deny").with(asClinicAdmin("admin", orgAlias))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DenyRefillRequestRequest("not medically indicated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("denied"))
                .andExpect(jsonPath("$.reviewNotes").value("not medically indicated"));

        org.assertj.core.api.Assertions.assertThat(notificationRepository.count()).isEqualTo(notificationCountBefore);
    }

    @Test
    void staffQueueEmbedsTheResolvedPatientAndMedicationNames() throws Exception {
        Clinic clinic = createClinic("refill-queue-names-" + UUID.randomUUID(), "Queue Names Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-queue-names", "Amoxicillin", null);

        mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-queue-names"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pharmacy/refill-requests?status=requested").with(asPharmacist("pharm", orgAlias)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patientName").value("Portal Patient"))
                .andExpect(jsonPath("$[0].medicationName").value("Amoxicillin"));
    }

    @Test
    void roleGatesHoldOnBothSides() throws Exception {
        Clinic clinic = createClinic("refill-role-" + UUID.randomUUID(), "Role Clinic");
        String orgAlias = clinic.getKeycloakOrgId();

        mockMvc.perform(get("/api/pharmacy/refill-requests").with(asProvider("prov", orgAlias)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/my-prescriptions").with(asFrontDesk("fd", orgAlias)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantRefillRequestActionsAreNotFound() throws Exception {
        Clinic clinic = createClinic("refill-tenant-a-" + UUID.randomUUID(), "Clinic A");
        Prescription prescription = seedOwnedPrescription(clinic.getId(), "patient-tenant-a", "Amoxicillin", null);

        String body = mockMvc.perform(post("/api/my-prescriptions/" + prescription.getId() + "/refill-requests").with(asPatient("patient-tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateRefillRequestRequest(null))))
                .andReturn().getResponse().getContentAsString();
        UUID refillId = UUID.fromString(objectMapper.readTree(body).get("id").asText());

        Clinic otherClinic = createClinic("refill-tenant-b-" + UUID.randomUUID(), "Clinic B");
        mockMvc.perform(post("/api/pharmacy/refill-requests/" + refillId + "/approve").with(asPharmacist("pharm", otherClinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
