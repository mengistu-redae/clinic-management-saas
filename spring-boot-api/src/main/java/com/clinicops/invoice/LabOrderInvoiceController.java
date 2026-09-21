package com.clinicops.invoice;

import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public LabOrderInvoiceController(
            LabOrderRepository labOrderRepository, InvoiceRepository invoiceRepository, InvoiceService invoiceService) {
        this.labOrderRepository = labOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
    }

    @GetMapping("/api/lab-orders/{orderId}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Invoice invoice(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        return invoiceRepository.findByLabOrderIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for lab order " + orderId));
    }

    @PostMapping("/api/lab-orders/{orderId}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Invoice generateInvoice(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        return invoiceService.generateForLabOrder(orderId, tenantId);
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
