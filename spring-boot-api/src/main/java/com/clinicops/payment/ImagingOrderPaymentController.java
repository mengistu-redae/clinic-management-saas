package com.clinicops.payment;

import com.clinicops.imaging.ImagingOrderRepository;
import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
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

/** Mirrors AppointmentPaymentController exactly - recording a payment is a deliberate, separate staff action; no imaging_technologist access (billing isn't their job). */
@RestController
public class ImagingOrderPaymentController {

    private final ImagingOrderRepository imagingOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final CurrentUserService currentUserService;

    public ImagingOrderPaymentController(
            ImagingOrderRepository imagingOrderRepository,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            CurrentUserService currentUserService) {
        this.imagingOrderRepository = imagingOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/imaging-orders/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Payment> payments(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        return paymentRepository.findAllByImagingOrderIdAndTenantId(id, tenantId);
    }

    @PostMapping("/api/imaging-orders/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Payment recordPayment(@PathVariable UUID id, @Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        UUID invoiceId = resolveInvoiceId(id, tenantId, request.invoiceId());
        UUID recordedBy = currentUserService.resolveInternalUserId(jwt);
        return paymentService.recordPayment(tenantId, null, null, null, id, invoiceId, request, recordedBy);
    }

    private UUID resolveInvoiceId(UUID imagingOrderId, UUID tenantId, UUID requestedInvoiceId) {
        if (requestedInvoiceId == null) {
            return null;
        }
        Invoice invoice = invoiceRepository.findByImagingOrderIdAndTenantId(imagingOrderId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No invoice exists for this imaging order"));
        if (!invoice.getId().equals(requestedInvoiceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceId does not match this imaging order's own invoice");
        }
        return invoice.getId();
    }

    private void requireOwnedOrder(UUID id, UUID tenantId) {
        imagingOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Imaging order not found: " + id));
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
