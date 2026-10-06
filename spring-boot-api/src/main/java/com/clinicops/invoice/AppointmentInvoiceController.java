package com.clinicops.invoice;

import com.clinicops.appointment.AppointmentRepository;
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

/** Generating an invoice is a deliberate, separate staff action, same shape as AppointmentPaymentController - no status gate, no delete, no update. */
@RestController
public class AppointmentInvoiceController {

    private final AppointmentRepository appointmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final PaymentRepository paymentRepository;

    public AppointmentInvoiceController(
            AppointmentRepository appointmentRepository,
            InvoiceRepository invoiceRepository,
            InvoiceService invoiceService,
            InvoicePdfService invoicePdfService,
            PaymentRepository paymentRepository) {
        this.appointmentRepository = appointmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.paymentRepository = paymentRepository;
    }

    @GetMapping("/api/appointments/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public InvoiceWithBalance invoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        Invoice invoice = invoiceRepository.findByAppointmentIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for appointment " + id));
        return withBalance(invoice, tenantId);
    }

    @PostMapping("/api/appointments/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public InvoiceWithBalance generateInvoice(@PathVariable UUID id, @RequestBody(required = false) GenerateInvoiceRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        Invoice invoice = invoiceService.generateForAppointment(id, tenantId, request);
        return withBalance(invoice, tenantId);
    }

    private InvoiceWithBalance withBalance(Invoice invoice, UUID tenantId) {
        return InvoiceWithBalance.of(invoice, paymentRepository.sumAmountByInvoiceIdAndTenantId(invoice.getId(), tenantId));
    }

    /** Rendered on demand, never persisted - see InvoicePdfService's own javadoc for why. */
    @GetMapping("/api/appointments/{id}/invoice/pdf")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        Invoice invoice = invoiceRepository.findByAppointmentIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for appointment " + id));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(invoicePdfService.renderForAppointment(invoice));
    }

    private void requireOwnedAppointment(UUID appointmentId, UUID tenantId) {
        appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
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
