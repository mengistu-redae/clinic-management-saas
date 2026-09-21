package com.clinicops.invoice;

import com.clinicops.appointment.AppointmentRepository;
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

/** Generating an invoice is a deliberate, separate staff action, same shape as AppointmentPaymentController - no status gate, no delete, no update. */
@RestController
public class AppointmentInvoiceController {

    private final AppointmentRepository appointmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;

    public AppointmentInvoiceController(
            AppointmentRepository appointmentRepository, InvoiceRepository invoiceRepository, InvoiceService invoiceService) {
        this.appointmentRepository = appointmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
    }

    @GetMapping("/api/appointments/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Invoice invoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        return invoiceRepository.findByAppointmentIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No invoice generated yet for appointment " + id));
    }

    @PostMapping("/api/appointments/{id}/invoice")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Invoice generateInvoice(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        return invoiceService.generateForAppointment(id, tenantId);
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
