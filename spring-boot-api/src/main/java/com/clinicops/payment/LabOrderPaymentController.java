package com.clinicops.payment;

import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.paymentgateway.PaymentGatewayException;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Separate from AppointmentPaymentController - a different owner column and base path nesting, same shared Payment entity/CreatePaymentRequest shape. No delete. */
@RestController
public class LabOrderPaymentController {

    private final LabOrderRepository labOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final CurrentUserService currentUserService;

    public LabOrderPaymentController(
            LabOrderRepository labOrderRepository,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            CurrentUserService currentUserService) {
        this.labOrderRepository = labOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/lab-orders/{orderId}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Payment> payments(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        return paymentRepository.findAllByLabOrderIdAndTenantId(orderId, tenantId);
    }

    @PostMapping("/api/lab-orders/{orderId}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Payment recordPayment(@PathVariable UUID orderId, @Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        UUID invoiceId = resolveInvoiceId(orderId, tenantId, request.invoiceId());
        UUID recordedBy = currentUserService.resolveInternalUserId(jwt);
        return paymentService.recordPayment(tenantId, null, orderId, null, null, invoiceId, request, recordedBy);
    }

    /** Only this lab order's own already-issued invoice may be linked - never an arbitrary id from another owner/tenant. */
    private UUID resolveInvoiceId(UUID labOrderId, UUID tenantId, UUID requestedInvoiceId) {
        if (requestedInvoiceId == null) {
            return null;
        }
        Invoice invoice = invoiceRepository.findByLabOrderIdAndTenantId(labOrderId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No invoice exists for this lab order"));
        if (!invoice.getId().equals(requestedInvoiceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceId does not match this lab order's own invoice");
        }
        return invoice.getId();
    }

    private void requireOwnedLabOrder(UUID labOrderId, UUID tenantId) {
        labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + labOrderId));
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

    @ExceptionHandler(PaymentExceedsInvoiceBalanceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleExceedsBalance(PaymentExceedsInvoiceBalanceException e) {
        return e.getMessage();
    }
}
