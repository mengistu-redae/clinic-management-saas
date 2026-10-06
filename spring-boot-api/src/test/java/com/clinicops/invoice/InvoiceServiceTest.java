package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.imaging.ImagingOrderRepository;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.payment.PaymentRepository;
import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.pharmacy.MedicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 46 - the discount math (applied before tax) and its cross-field validation, plus the new deposit-linking side effect. */
class InvoiceServiceTest {

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final AppointmentTypeRepository appointmentTypeRepository = mock(AppointmentTypeRepository.class);
    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);
    private final DispenseRecordRepository dispenseRecordRepository = mock(DispenseRecordRepository.class);
    private final MedicationRepository medicationRepository = mock(MedicationRepository.class);
    private final ClinicSettingsService clinicSettingsService = mock(ClinicSettingsService.class);
    private final ImagingOrderRepository imagingOrderRepository = mock(ImagingOrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final InvoiceService service = new InvoiceService(
            invoiceRepository, appointmentRepository, appointmentTypeRepository, labOrderRepository,
            dispenseRecordRepository, medicationRepository, clinicSettingsService, imagingOrderRepository, paymentRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();

    @Test
    void noDiscountMatchesTodaysExactBehavior() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("10"));

        Invoice invoice = service.generateForAppointment(appointmentId, tenantId, null);

        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("0");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("10.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("110.00");
    }

    @Test
    void aPercentDiscountIsAppliedToTheSubtotalBeforeTax() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("10"));
        GenerateInvoiceRequest request = new GenerateInvoiceRequest(new BigDecimal("20"), null, "Senior discount");

        Invoice invoice = service.generateForAppointment(appointmentId, tenantId, request);

        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(invoice.getDiscountReason()).isEqualTo("Senior discount");
        // Tax is charged on the discounted $80, not the original $100.
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("8.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("88.00");
    }

    @Test
    void aFlatDiscountAmountIsHonoredDirectly() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("10"));
        GenerateInvoiceRequest request = new GenerateInvoiceRequest(null, new BigDecimal("15.00"), null);

        Invoice invoice = service.generateForAppointment(appointmentId, tenantId, request);

        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("15.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("8.50");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("93.50");
    }

    @Test
    void givingBothDiscountPercentAndDiscountAmountIsRejected() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("10"));
        GenerateInvoiceRequest request = new GenerateInvoiceRequest(new BigDecimal("10"), new BigDecimal("5.00"), null);

        assertThatThrownBy(() -> service.generateForAppointment(appointmentId, tenantId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at most one");
    }

    @Test
    void aDiscountAmountLargerThanTheSubtotalIsRejected() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("10"));
        GenerateInvoiceRequest request = new GenerateInvoiceRequest(null, new BigDecimal("150.00"), null);

        assertThatThrownBy(() -> service.generateForAppointment(appointmentId, tenantId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot exceed the subtotal");
    }

    @Test
    void generatingAnInvoiceLinksAnyPreExistingUnlinkedPaymentForTheSameAppointment() {
        stubAppointment(new BigDecimal("100.00"), new BigDecimal("0"));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice i = invocation.getArgument(0);
            i.setId(UUID.randomUUID());
            return i;
        });

        Invoice invoice = service.generateForAppointment(appointmentId, tenantId, null);

        verify(paymentRepository).linkUnlinkedPaymentsForAppointment(tenantId, appointmentId, invoice.getId());
    }

    private void stubAppointment(BigDecimal priceAmount, BigDecimal taxRatePercent) {
        UUID appointmentTypeId = UUID.randomUUID();
        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setTenantId(tenantId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        AppointmentType type = new AppointmentType();
        type.setId(appointmentTypeId);
        type.setTenantId(tenantId);
        type.setPriceAmount(priceAmount);

        when(invoiceRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        when(appointmentTypeRepository.findByIdAndTenantId(appointmentTypeId, tenantId)).thenReturn(Optional.of(type));
        when(clinicSettingsService.resolve(tenantId)).thenReturn(new EffectiveClinicSettings(
                taxRatePercent, null, null, 0L, 0, "UTC", null, null, null, null));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
