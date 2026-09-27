package com.clinicops.payment;

import com.clinicops.accounting.JournalService;
import com.clinicops.paymentgateway.PaymentGatewayClient;
import com.clinicops.paymentgateway.RefundResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final PaymentGatewayClient gatewayClient = mock(PaymentGatewayClient.class);
    private final JournalService journalService = mock(JournalService.class);
    private final RefundService service = new RefundService(paymentRepository, refundRepository, gatewayClient, journalService);

    private Payment gatewayChargedPayment(UUID tenantId, UUID paymentId, BigDecimal amount) {
        Payment payment = new Payment();
        payment.setId(paymentId);
        payment.setTenantId(tenantId);
        payment.setAmount(amount);
        payment.setGatewayTransactionId("mock_chg_original");
        payment.setGatewayStatus("succeeded");
        when(paymentRepository.findByIdAndTenantId(paymentId, tenantId)).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void aFullRefundCallsTheGatewayAndMarksThePaymentRefunded() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = gatewayChargedPayment(tenantId, paymentId, new BigDecimal("50.00"));
        when(refundRepository.sumAmountByPaymentId(paymentId)).thenReturn(BigDecimal.ZERO);
        when(gatewayClient.refund("mock_chg_original", new BigDecimal("50.00")))
                .thenReturn(new RefundResult("mock_rfd_1", "succeeded"));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Refund refund = service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("50.00"), "no-show waiver"), UUID.randomUUID());

        assertThat(refund.getGatewayRefundTransactionId()).isEqualTo("mock_rfd_1");
        assertThat(payment.getGatewayStatus()).isEqualTo("refunded");
        verify(gatewayClient).refund("mock_chg_original", new BigDecimal("50.00"));
    }

    @Test
    void aPartialRefundMarksThePaymentPartiallyRefunded() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = gatewayChargedPayment(tenantId, paymentId, new BigDecimal("100.00"));
        when(refundRepository.sumAmountByPaymentId(paymentId)).thenReturn(BigDecimal.ZERO);
        when(gatewayClient.refund(anyString(), any())).thenReturn(new RefundResult("mock_rfd_2", "succeeded"));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("40.00"), null), UUID.randomUUID());

        assertThat(payment.getGatewayStatus()).isEqualTo("partially_refunded");
    }

    @Test
    void aSecondPartialRefundThatReachesTheFullAmountFlipsToRefunded() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = gatewayChargedPayment(tenantId, paymentId, new BigDecimal("100.00"));
        // 60 already refunded from an earlier call - this one brings the cumulative total to exactly 100.
        when(refundRepository.sumAmountByPaymentId(paymentId)).thenReturn(new BigDecimal("60.00"));
        when(gatewayClient.refund(anyString(), any())).thenReturn(new RefundResult("mock_rfd_3", "succeeded"));
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("40.00"), null), UUID.randomUUID());

        assertThat(payment.getGatewayStatus()).isEqualTo("refunded");
    }

    @Test
    void aRefundExceedingTheRemainingAmountIsRejectedAndNeverTouchesTheGatewayOrSavesAnything() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        gatewayChargedPayment(tenantId, paymentId, new BigDecimal("50.00"));
        when(refundRepository.sumAmountByPaymentId(paymentId)).thenReturn(new BigDecimal("30.00"));

        assertThatThrownBy(() -> service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("30.00"), null), UUID.randomUUID()))
                .isInstanceOf(RefundExceedsPaymentException.class);

        verify(gatewayClient, never()).refund(anyString(), any());
        verify(refundRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundingAPaymentThatWasNeverGatewayChargedSkipsTheGatewayCallEntirely() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment cashPayment = new Payment();
        cashPayment.setId(paymentId);
        cashPayment.setTenantId(tenantId);
        cashPayment.setAmount(new BigDecimal("20.00"));
        // No gatewayTransactionId - e.g. a cash payment, or a fee_auto_charge row.
        when(paymentRepository.findByIdAndTenantId(paymentId, tenantId)).thenReturn(Optional.of(cashPayment));
        when(refundRepository.sumAmountByPaymentId(paymentId)).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Refund refund = service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("20.00"), "waived"), UUID.randomUUID());

        assertThat(refund.getGatewayRefundTransactionId()).isNull();
        assertThat(cashPayment.getGatewayStatus()).isEqualTo("refunded");
        verify(gatewayClient, never()).refund(anyString(), any());
    }

    @Test
    void anUnknownPaymentIdThrowsNoSuchElement() {
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndTenantId(paymentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(tenantId, paymentId, new CreateRefundRequest(new BigDecimal("1.00"), null), UUID.randomUUID()))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
