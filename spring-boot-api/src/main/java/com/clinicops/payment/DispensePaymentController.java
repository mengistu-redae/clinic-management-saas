package com.clinicops.payment;

import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
import com.clinicops.pharmacy.DispenseRecordRepository;
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

/**
 * Same shape as AppointmentPaymentController/LabOrderPaymentController -
 * recording a payment is a deliberate, separate staff action, no delete.
 * Phase 31's own deliberate widening: pharmacist gets the same read+write
 * access as front_desk/clinic_admin here (unlike provider's read-only role
 * on appointment payments) - pharmacist is genuinely the counter-staff
 * role for a pharmacy sale.
 */
@RestController
public class DispensePaymentController {

    private final DispenseRecordRepository dispenseRecordRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final CurrentUserService currentUserService;

    public DispensePaymentController(
            DispenseRecordRepository dispenseRecordRepository,
            InvoiceRepository invoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            CurrentUserService currentUserService) {
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/dispense-records/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PHARMACIST')")
    public List<Payment> payments(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedDispenseRecord(id, tenantId);
        return paymentRepository.findAllByDispenseRecordIdAndTenantId(id, tenantId);
    }

    @PostMapping("/api/dispense-records/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PHARMACIST')")
    public Payment recordPayment(@PathVariable UUID id, @Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedDispenseRecord(id, tenantId);
        UUID invoiceId = resolveInvoiceId(id, tenantId, request.invoiceId());
        UUID recordedBy = currentUserService.resolveInternalUserId(jwt);
        return paymentService.recordPayment(tenantId, null, null, id, null, invoiceId, request, recordedBy);
    }

    /** Only this dispense record's own already-issued invoice may be linked - never an arbitrary id from another owner/tenant. */
    private UUID resolveInvoiceId(UUID dispenseRecordId, UUID tenantId, UUID requestedInvoiceId) {
        if (requestedInvoiceId == null) {
            return null;
        }
        Invoice invoice = invoiceRepository.findByDispenseRecordIdAndTenantId(dispenseRecordId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No invoice exists for this dispense record"));
        if (!invoice.getId().equals(requestedInvoiceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceId does not match this dispense record's own invoice");
        }
        return invoice.getId();
    }

    private void requireOwnedDispenseRecord(UUID dispenseRecordId, UUID tenantId) {
        dispenseRecordRepository.findByIdAndTenantId(dispenseRecordId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Dispense record not found: " + dispenseRecordId));
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
