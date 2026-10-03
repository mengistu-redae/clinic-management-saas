package com.clinicops.invoice;

import com.clinicops.imaging.ImagingOrderRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public ImagingOrderInvoiceController(
            ImagingOrderRepository imagingOrderRepository,
            InvoiceRepository invoiceRepository,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService) {
        this.imagingOrderRepository = imagingOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
    }

    @GetMapping("/api/imaging-orders/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Invoice invoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        return invoiceRepository.findByImagingOrderIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for imaging order " + id));
    }

    @PostMapping("/api/imaging-orders/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Invoice generateInvoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedOrder(id, tenantId);
        return invoiceService.generateForImagingOrder(id, tenantId);
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
