package com.clinicops.payment;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.AppointmentWithSlotView;
import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
import com.clinicops.invoice.InvoiceWithBalance;
import com.clinicops.paymentgateway.PaymentGatewayException;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Phase 46 - lets a patient pay off their own appointment invoice directly,
 * no staff involvement. Appointment invoices only, full balance in one
 * payment (the user's own chosen scope) - lab/dispense/imaging invoices
 * stay staff-recorded only. Ownership resolved via
 * AppointmentRepository.findByIdAndCustomerUserIdWithSlot, the same
 * pattern VisitSummaryController.myVisitSummaryPdf already uses for this
 * identical shape - never TenantContext, a patient JWT carries none.
 */
@RestController
public class PatientInvoicePaymentController {

    private final AppointmentRepository appointmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final CurrentUserService currentUserService;

    public PatientInvoicePaymentController(
            AppointmentRepository appointmentRepository,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            CurrentUserService currentUserService) {
        this.appointmentRepository = appointmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/my-appointments/{id}/invoice")
    @PreAuthorize("hasRole('PATIENT')")
    public InvoiceWithBalance invoice(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        AppointmentWithSlotView appointment = requireOwnedAppointment(id, jwt);
        Invoice invoice = invoiceRepository.findByAppointmentIdAndTenantId(id, appointment.getTenantId())
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for appointment " + id));
        return withBalance(invoice, appointment.getTenantId());
    }

    @PostMapping("/api/my-appointments/{id}/invoice/pay")
    @PreAuthorize("hasRole('PATIENT')")
    public Payment pay(@PathVariable UUID id, @Valid @RequestBody PatientPayInvoiceRequest request, @AuthenticationPrincipal Jwt jwt) {
        AppointmentWithSlotView appointment = requireOwnedAppointment(id, jwt);
        UUID tenantId = appointment.getTenantId();
        Invoice invoice = invoiceRepository.findByAppointmentIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for appointment " + id));

        BigDecimal amountPaid = paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId);
        BigDecimal balanceDue = invoice.getTotalAmount().subtract(amountPaid);
        if (balanceDue.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing due on this invoice");
        }

        UUID recordedBy = currentUserService.resolveInternalUserId(jwt);
        CreatePaymentRequest createRequest = new CreatePaymentRequest(balanceDue, request.method(), request.transactionId(), invoice.getId());
        return paymentService.recordPayment(tenantId, id, null, null, null, invoice.getId(), createRequest, recordedBy);
    }

    private AppointmentWithSlotView requireOwnedAppointment(UUID appointmentId, Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return appointmentRepository.findByIdAndCustomerUserIdWithSlot(appointmentId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
    }

    private InvoiceWithBalance withBalance(Invoice invoice, UUID tenantId) {
        return InvoiceWithBalance.of(invoice, paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId));
    }

    public record PatientPayInvoiceRequest(@NotBlank String method, String transactionId) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(PaymentGatewayException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleGatewayError(PaymentGatewayException e) {
        return e.getMessage();
    }
}
