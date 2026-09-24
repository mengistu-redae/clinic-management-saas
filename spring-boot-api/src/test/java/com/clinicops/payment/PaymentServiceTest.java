package com.clinicops.payment;

import com.clinicops.paymentgateway.ChargeResult;
import com.clinicops.paymentgateway.PaymentGatewayClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final PaymentGatewayClient gatewayClient = mock(PaymentGatewayClient.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentService service = new PaymentService(gatewayClient, paymentRepository);

    @Test
    void chargesThroughTheGatewayBeforeSavingAndCopiesTheResultOntoThePayment() {
        UUID tenantId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID recordedBy = UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("50.00"), "card", "txn-1", invoiceId);
        when(gatewayClient.charge(new BigDecimal("50.00"), "card")).thenReturn(new ChargeResult("mock_chg_abc", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.recordPayment(tenantId, appointmentId, null, invoiceId, request, recordedBy);

        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(result.getLabOrderId()).isNull();
        assertThat(result.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(result.getGatewayTransactionId()).isEqualTo("mock_chg_abc");
        assertThat(result.getGatewayStatus()).isEqualTo("succeeded");
        verify(gatewayClient).charge(new BigDecimal("50.00"), "card");
    }

    @Test
    void aNullInvoiceIdStaysNullOnThePayment() {
        when(gatewayClient.charge(any(), any())).thenReturn(new ChargeResult("mock_chg_xyz", "succeeded"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Payment result = service.recordPayment(
                UUID.randomUUID(), null, UUID.randomUUID(), null,
                new CreatePaymentRequest(new BigDecimal("10.00"), "cash", null, null), UUID.randomUUID());

        assertThat(result.getInvoiceId()).isNull();
    }
}
