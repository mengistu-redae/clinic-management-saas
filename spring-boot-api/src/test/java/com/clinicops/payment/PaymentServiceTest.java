package com.clinicops.payment;

import com.clinicops.accounting.JournalService;
import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
import com.clinicops.paymentgateway.ChargeResult;
import com.clinicops.paymentgateway.PaymentGatewayClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentGatewayClient gatewayClient = mock(PaymentGatewayClient.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final JournalService journalService = mock(JournalService.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final PaymentService service = new PaymentService(gatewayClient, paymentRepository, journalService, invoiceRepository);

    @Test
    void chargesThroughTheGatewayBeforeSavingAndCopiesTheResultOntoThePayment() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID recordedBy = UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("50.00"), "card", "txn-1", invoiceId);
        stubInvoice(tenantId, invoiceId, new BigDecimal("100.00"));
        when(paymentRepository.sumAmountByInvoiceIdAndTenantId(invoiceId, tenantId)).thenReturn(BigDecimal.ZERO);
        when(gatewayClient.charge(new BigDecimal("50.00"), "card")).thenReturn(new ChargeResult("mock_chg_abc", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.recordPayment(tenantId, appointmentId, null, null, null, invoiceId, request, recordedBy);

        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(result.getLabOrderId()).isNull();
        assertThat(result.getDispenseRecordId()).isNull();
        assertThat(result.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(result.getGatewayTransactionId()).isEqualTo("mock_chg_abc");
        assertThat(result.getGatewayStatus()).isEqualTo("succeeded");
        verify(gatewayClient).charge(new BigDecimal("50.00"), "card");
    }

    @Test
    void aNullInvoiceIdStaysNullOnThePaymentAndSkipsTheBalanceCheck() {
        when(gatewayClient.charge(any(), any())).thenReturn(new ChargeResult("mock_chg_xyz", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.recordPayment(
                UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
                new CreatePaymentRequest(new BigDecimal("10.00"), "cash", null, null), UUID.randomUUID());

        assertThat(result.getInvoiceId()).isNull();
        verify(invoiceRepository, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void recordingAPaymentAgainstADispenseRecordLeavesTheOtherTwoOwnersNull() {
        UUID tenantId = UUID.randomUUID();
        UUID dispenseRecordId = UUID.randomUUID();
        when(gatewayClient.charge(any(), any())).thenReturn(new ChargeResult("mock_chg_dsp", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.recordPayment(tenantId, null, null, dispenseRecordId, null, null,
                new CreatePaymentRequest(new BigDecimal("25.00"), "cash", null, null), UUID.randomUUID());

        assertThat(result.getDispenseRecordId()).isEqualTo(dispenseRecordId);
        assertThat(result.getAppointmentId()).isNull();
        assertThat(result.getLabOrderId()).isNull();
    }

    // ---- Phase 46: overpayment is a hard block ----

    @Test
    void aPaymentThatWouldExceedTheInvoiceTotalIsRejected() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        stubInvoice(tenantId, invoiceId, new BigDecimal("50.00"));
        when(paymentRepository.sumAmountByInvoiceIdAndTenantId(invoiceId, tenantId)).thenReturn(new BigDecimal("40.00"));
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("20.00"), "cash", null, invoiceId);

        assertThatThrownBy(() -> service.recordPayment(tenantId, UUID.randomUUID(), null, null, null, invoiceId, request, UUID.randomUUID()))
                .isInstanceOf(PaymentExceedsInvoiceBalanceException.class)
                .hasMessageContaining("60.00")
                .hasMessageContaining("50.00");
        verify(gatewayClient, never()).charge(any(), any());
    }

    @Test
    void aPaymentThatExactlyClearsTheRemainingBalanceSucceeds() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        stubInvoice(tenantId, invoiceId, new BigDecimal("50.00"));
        when(paymentRepository.sumAmountByInvoiceIdAndTenantId(invoiceId, tenantId)).thenReturn(new BigDecimal("30.00"));
        when(gatewayClient.charge(any(), any())).thenReturn(new ChargeResult("mock_chg_exact", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("20.00"), "cash", null, invoiceId);

        Payment result = service.recordPayment(tenantId, UUID.randomUUID(), null, null, null, invoiceId, request, UUID.randomUUID());

        assertThat(result.getAmount()).isEqualByComparingTo("20.00");
    }

    private void stubInvoice(UUID tenantId, UUID invoiceId, BigDecimal totalAmount) {
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTenantId(tenantId);
        invoice.setTotalAmount(totalAmount);
        when(invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)).thenReturn(Optional.of(invoice));
    }
}
