package com.clinicops.invoice;

/** Maps to HTTP 409 in AppointmentInvoiceController/LabOrderInvoiceController - an invoice is an immutable financial record, generated at most once per owner. */
public class InvoiceAlreadyExistsException extends RuntimeException {
    public InvoiceAlreadyExistsException(String message) {
        super(message);
    }
}
