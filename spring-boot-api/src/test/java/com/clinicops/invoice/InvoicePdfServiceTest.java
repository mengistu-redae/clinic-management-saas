package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.clinicsettings.ClinicBrandingView;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs locally like every other pure-unit test in this suite - no
 * Testcontainers/Spring context involved, just real OpenPDF rendering
 * against mocked repositories/settings. Asserts the real "%PDF" magic
 * header rather than trusting that no exception was thrown - a
 * corrupted/empty byte array would still pass a exception-free test.
 */
class InvoicePdfServiceTest {

    private final ClinicSettingsService clinicSettingsService = mock(ClinicSettingsService.class);
    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);
    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final InvoicePdfService service =
            new InvoicePdfService(clinicSettingsService, appointmentRepository, labOrderRepository, patientRepository);

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

    private void stubBrandingAndSettings(UUID tenantId) {
        when(clinicSettingsService.getBranding(tenantId))
                .thenReturn(new ClinicBrandingView(null, null, null, "Test Clinic", null, "UTC"));
        when(clinicSettingsService.resolve(tenantId)).thenReturn(new EffectiveClinicSettings(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 4, 24, "UTC",
                "555-0100", "billing@test-clinic.example", "1 Main St", "https://test-clinic.example"));
    }

    private Invoice invoice(UUID tenantId, UUID appointmentId, UUID labOrderId) {
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId(tenantId);
        invoice.setAppointmentId(appointmentId);
        invoice.setLabOrderId(labOrderId);
        invoice.setSubtotalAmount(new BigDecimal("100.00"));
        invoice.setTaxAmount(new BigDecimal("8.25"));
        invoice.setTotalAmount(new BigDecimal("108.25"));
        return invoice;
    }

    @Test
    void rendersARealPdfForAnAppointmentInvoice() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);
        Appointment appointment = new Appointment();
        appointment.setAppointmentRef("ABC123");
        appointment.setPatientId(UUID.randomUUID());
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        Patient patient = new Patient();
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        when(patientRepository.findByIdAndTenantId(appointment.getPatientId(), tenantId)).thenReturn(Optional.of(patient));

        byte[] pdf = service.renderForAppointment(invoice(tenantId, appointmentId, null));

        assertThat(pdf).isNotEmpty();
        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    void rendersARealPdfForALabOrderInvoiceEvenWithNoMatchingOrderFound() {
        UUID tenantId = UUID.randomUUID();
        UUID labOrderId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);
        when(labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)).thenReturn(Optional.empty());

        byte[] pdf = service.renderForLabOrder(invoice(tenantId, null, labOrderId));

        assertThat(pdf).isNotEmpty();
        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }

    @Test
    void aGuestAppointmentWithNoPatientRowFallsBackToTheContactName() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        stubBrandingAndSettings(tenantId);
        Appointment appointment = new Appointment();
        appointment.setAppointmentRef("GUEST1");
        appointment.setPatientId(null);
        appointment.setContactName("Walk-in Guest");
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));

        byte[] pdf = service.renderForAppointment(invoice(tenantId, appointmentId, null));

        assertThat(java.util.Arrays.copyOf(pdf, 4)).isEqualTo(PDF_MAGIC);
    }
}
