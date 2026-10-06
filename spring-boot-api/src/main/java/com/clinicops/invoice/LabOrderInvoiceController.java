package com.clinicops.invoice;

import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.payment.PaymentRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Separate from AppointmentInvoiceController - a different owner column and base path nesting, same shared Invoice entity/InvoiceService, same shape as LabOrderPaymentController. */
@RestController
public class LabOrderInvoiceController {

    private final LabOrderRepository labOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final PaymentRepository paymentRepository;

    public LabOrderInvoiceController(
            LabOrderRepository labOrderRepository,
            InvoiceRepository invoiceRepository,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService,
            PaymentRepository paymentRepository) {
        this.labOrderRepository = labOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/api/lab-orders/{orderId}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public InvoiceWithBalance invoice(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        Invoice invoice = invoiceRepository.findByLabOrderIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for lab order " + orderId));
        return withBalance(invoice, tenantId);
    }

    @PostMapping("/api/lab-orders/{orderId}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public InvoiceWithBalance generateInvoice(@PathVariable UUID orderId, @RequestBody(required = false) GenerateInvoiceRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        Invoice invoice = invoiceService.generateForLabOrder(orderId, tenantId, request);
        return withBalance(invoice, tenantId);
    }

    private InvoiceWithBalance withBalance(Invoice invoice, UUID tenantId) {
        return InvoiceWithBalance.of(invoice, paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId));
    }

    /** Rendered on demand, never persisted - see InvoicePdfService's own javadoc for why. */
    @GetMapping("/api/lab-orders/{orderId}/invoice/pdf")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        Invoice invoice = invoiceRepository.findByLabOrderIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for lab order " + orderId));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(invoicePdfService.renderForLabOrder(invoice));
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

    @ExceptionHandler(InvoiceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyExists(InvoiceAlreadyExistsException e) {
        return e.getMessage();
    }
}
