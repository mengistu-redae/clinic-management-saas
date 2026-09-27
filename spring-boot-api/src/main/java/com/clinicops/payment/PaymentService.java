package com.clinicops.payment;

import com.clinicops.accounting.JournalService;
import com.clinicops.paymentgateway.ChargeResult;
import com.clinicops.paymentgateway.PaymentGatewayClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A dedicated bean (not plain controller-calls-repository CRUD) because
 * charging through the gateway is genuine cross-cutting logic shared
 * identically by AppointmentPaymentController and LabOrderPaymentController
 * - same "cross-cutting dependency" reasoning ClinicSettingsService/
 * InvoiceService already established. The fee_auto_charge shortcut in
 * Cancellation/Reschedule/LabOrderCancellationService deliberately bypasses
 * this entirely (it saves a Payment directly) - that row was never a
 * genuine gateway charge, and this phase doesn't change that (those three
 * services call JournalService directly themselves instead, for the same
 * auto-posting effect).
 */
@Service
public class PaymentService {

    private final PaymentGatewayClient gatewayClient;
    private final PaymentRepository paymentRepository;
    private final JournalService journalService;

    public PaymentService(PaymentGatewayClient gatewayClient, PaymentRepository paymentRepository, JournalService journalService) {
        this.gatewayClient = gatewayClient;
        this.paymentRepository = paymentRepository;
        this.journalService = journalService;
    }

    /**
     * Exactly one of appointmentId/labOrderId must be set by the caller
     * (mirrors Payment's own exactly-one-owner shape); invoiceId is
     * whatever the caller already validated belongs to that same owner, or
     * null.
     */
    @Transactional
    public Payment recordPayment(UUID tenantId, UUID appointmentId, UUID labOrderId, UUID invoiceId, CreatePaymentRequest request, UUID recordedBy) {
        Payment payment = new Payment();
        payment.setTenantId(tenantId);
        payment.setAppointmentId(appointmentId);
        payment.setLabOrderId(labOrderId);
        payment.setInvoiceId(invoiceId);
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        payment.setTransactionId(request.transactionId());
        payment.setRecordedBy(recordedBy);

        ChargeResult result = gatewayClient.charge(request.amount(), request.method());
        payment.setGatewayTransactionId(result.transactionId());
        payment.setGatewayStatus(result.status());

        Payment saved = paymentRepository.save(payment);
        journalService.postForPayment(saved);
        return saved;
    }
}
