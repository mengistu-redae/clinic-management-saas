package com.clinicops.invoice;

import com.clinicops.imaging.ImagingOrderRepository;
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

/** Mirrors AppointmentInvoiceController/LabOrderInvoiceController exactly - no imaging_technologist access (billing isn't their job, matching lab_technician's own exclusion). */
@RestController
public class ImagingOrderInvoiceController {

    private final ImagingOrderRepository imagingOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final PaymentRepository paymentRepository;

    public ImagingOrderInvoiceController(
            ImagingOrderRepository imagingOrderRepository,
            InvoiceRepository invoiceRepository,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService,
            PaymentRepository paymentRepository) {
        this.imagingOrderRepository = imagingOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/api/imaging-orders/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public InvoiceWithBalance invoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        Invoice invoice = invoiceRepository.findByImagingOrderIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for imaging order " + id));
        return withBalance(invoice, tenantId);
    }

    @PostMapping("/api/imaging-orders/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public InvoiceWithBalance generateInvoice(@PathVariable UUID id, @RequestBody(required = false) GenerateInvoiceRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        Invoice invoice = invoiceService.generateForImagingOrder(id, tenantId, request);
        return withBalance(invoice, tenantId);
    }

    private InvoiceWithBalance withBalance(Invoice invoice, UUID tenantId) {
        return InvoiceWithBalance.of(invoice, paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId));
    }

    @GetMapping("/api/imaging-orders/{id}/invoice/pdf")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        Invoice invoice = invoiceRepository.findByImagingOrderIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for imaging order " + id));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(invoicePdfService.renderForImagingOrder(invoice));
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

    @ExceptionHandler(InvoiceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyExists(InvoiceAlreadyExistsException e) {
        return e.getMessage();
    }
}
