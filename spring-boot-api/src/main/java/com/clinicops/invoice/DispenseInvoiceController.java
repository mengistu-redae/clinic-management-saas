package com.clinicops.invoice;

import com.clinicops.payment.PaymentRepository;
import com.clinicops.pharmacy.DispenseRecordRepository;
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

/**
 * Same shape as AppointmentInvoiceController - no status gate, no delete,
 * no update. Phase 31's own deliberate widening: pharmacist gets the same
 * access as front_desk/clinic_admin on all three endpoints here (including
 * generate), unlike AppointmentInvoiceController's 2-role generate gate -
 * pharmacist is the counter-staff role for a pharmacy sale.
 */
@RestController
public class DispenseInvoiceController {

    private final DispenseRecordRepository dispenseRecordRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final PaymentRepository paymentRepository;

    public DispenseInvoiceController(
            DispenseRecordRepository dispenseRecordRepository,
            InvoiceRepository invoiceRepository,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService,
            PaymentRepository paymentRepository) {
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/api/dispense-records/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PHARMACIST')")
    public InvoiceWithBalance invoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedDispenseRecord(id, tenantId);
        Invoice invoice = invoiceRepository.findByDispenseRecordIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for dispense record " + id));
        return withBalance(invoice, tenantId);
    }

    @PostMapping("/api/dispense-records/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PHARMACIST')")
    public InvoiceWithBalance generateInvoice(@PathVariable UUID id, @RequestBody(required = false) GenerateInvoiceRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedDispenseRecord(id, tenantId);
        Invoice invoice = invoiceService.generateForDispenseRecord(id, tenantId, request);
        return withBalance(invoice, tenantId);
    }

    private InvoiceWithBalance withBalance(Invoice invoice, UUID tenantId) {
        return InvoiceWithBalance.of(invoice, paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId));
    }

    @GetMapping("/api/dispense-records/{id}/invoice/pdf")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PHARMACIST')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedDispenseRecord(id, tenantId);
        Invoice invoice = invoiceRepository.findByDispenseRecordIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for dispense record " + id));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(invoicePdfService.renderForDispenseRecord(invoice));
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

    @ExceptionHandler(InvoiceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyExists(InvoiceAlreadyExistsException e) {
        return e.getMessage();
    }
}
