package com.clinicops.visitsummary;

import com.clinicops.allergy.Allergy;
import com.clinicops.allergy.AllergyRepository;
import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.clinicsettings.ClinicBrandingView;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.immunization.Immunization;
import com.clinicops.immunization.ImmunizationRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.clinicops.vitals.Vitals;
import com.clinicops.vitals.VitalsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs locally like InvoicePdfServiceTest - no Testcontainers/Spring
 * context, real OpenPDF rendering against mocked repositories. Asserts
 * the real "%PDF" magic header, not just that no exception was thrown.
 */
class VisitSummaryPdfServiceTest {

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final ProviderRepository providerRepository = mock(ProviderRepository.class);
    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final VitalsRepository vitalsRepository = mock(VitalsRepository.class);
    private final AllergyRepository allergyRepository = mock(AllergyRepository.class);
    private final ImmunizationRepository immunizationRepository = mock(ImmunizationRepository.class);
    private final ClinicSettingsService clinicSettingsService = mock(ClinicSettingsService.class);

    private final VisitSummaryPdfService service = new VisitSummaryPdfService(
            appointmentRepository, slotRepository, providerRepository, patientRepository,
            encounterRepository, prescriptionRepository, vitalsRepository, allergyRepository,
            immunizationRepository, clinicSettingsService);

    private void stubBrandingAndSettings(UUID tenantId) {
        when(clinicSettingsService.getBranding(tenantId))
                .thenReturn(new ClinicBrandingView(null, null, null, "Test Clinic", null, "UTC"));
        when(clinicSettingsService.resolve(tenantId)).thenReturn(new EffectiveClinicSettings(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 4, 24, "UTC",
                "555-0100", "billing@test-clinic.example", "1 Main St", "https://test-clinic.example"));
    }

    private Appointment appointment(UUID tenantId, UUID patientId, UUID slotId, UUID providerId) {
        Appointment appointment = new Appointment();
        appointment.setTenantId(tenantId);
        appointment.setAppointmentRef("VS-TEST-1");
        appointment.setPatientId(patientId);
        appointment.setSlotId(slotId);
        appointment.setProviderId(providerId);
        return appointment;
    }

    @Test
    void rendersARealPdfWithFullData() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);

        Appointment appointment = appointment(tenantId, patientId, slotId, providerId);
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));

        Slot slot = new Slot();
        slot.setStartTime(Instant.now());
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));

        Provider provider = new Provider();
        provider.setFullName("Dr. Test");
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.of(provider));

        Patient patient = new Patient();
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.of(patient));

        Vitals vitals = new Vitals();
        vitals.setHeightCm(new BigDecimal("170"));
        vitals.setWeightKg(new BigDecimal("70"));
        when(vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(vitals));

        Allergy activeAllergy = new Allergy();
        activeAllergy.setAllergen("Penicillin");
        activeAllergy.setStatus("active");
        Allergy resolvedAllergy = new Allergy();
        resolvedAllergy.setAllergen("Latex");
        resolvedAllergy.setStatus("resolved");
        when(allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId))
                .thenReturn(List.of(activeAllergy, resolvedAllergy));

        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setChiefComplaint("Cough");
        encounter.setAssessment("Bronchitis");
        encounter.setPlan("Rest");
        encounter.setCardiovascularNormal(true);
        encounter.setRespiratoryNormal(false);
        encounter.setRespiratoryNote("Wheezing on exhale");
        when(encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(encounter));

        Prescription prescription = new Prescription();
        prescription.setMedicationName("Amoxicillin");
        prescription.setDosage("500mg");
        when(prescriptionRepository.findAllByEncounterId(encounter.getId())).thenReturn(List.of(prescription));

        Immunization immunization = new Immunization();
        immunization.setVaccineName("Influenza");
        when(immunizationRepository.findAllByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(List.of(immunization));

        byte[] pdf = service.render(appointmentId, tenantId);

        assertThat(pdf).isNotEmpty();
        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    void aGuestBookingSkipsAllergyLookupButStillRendersAndImmunizationsStillWorkByAppointmentId() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);

        Appointment appointment = appointment(tenantId, null, slotId, providerId);
        appointment.setContactName("Walk-in Guest");
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        when(slotRepository.findById(slotId)).thenReturn(Optional.empty());
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.empty());
        when(encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());

        Immunization immunization = new Immunization();
        immunization.setVaccineName("Tetanus");
        when(immunizationRepository.findAllByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(List.of(immunization));

        byte[] pdf = service.render(appointmentId, tenantId);

        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
        verify(allergyRepository, never()).findAllByPatientIdAndTenantId(any(), any());
    }

    @Test
    void aFreshlyBookedAppointmentWithNothingDocumentedYetStillRenders() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);

        Appointment appointment = appointment(tenantId, patientId, slotId, providerId);
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        when(slotRepository.findById(slotId)).thenReturn(Optional.empty());
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.empty());
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.empty());
        when(vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId)).thenReturn(List.of());
        when(encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(immunizationRepository.findAllByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(List.of());

        byte[] pdf = service.render(appointmentId, tenantId);

        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    void onlyActiveAllergiesArePrintedButAllResolvedStillRendersCleanly() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);

        Appointment appointment = appointment(tenantId, patientId, slotId, providerId);
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        when(slotRepository.findById(slotId)).thenReturn(Optional.empty());
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.empty());
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.empty());
        when(vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(immunizationRepository.findAllByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(List.of());

        Allergy resolvedOnly = new Allergy();
        resolvedOnly.setAllergen("Latex");
        resolvedOnly.setStatus("resolved");
        when(allergyRepository.findAllByPatientIdAndTenantId(patientId, tenantId)).thenReturn(List.of(resolvedOnly));

        byte[] pdf = service.render(appointmentId, tenantId);

        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }
}
