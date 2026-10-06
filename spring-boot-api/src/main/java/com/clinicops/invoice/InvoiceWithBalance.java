package com.clinicops.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 46 - every Invoice field plus the derived amountPaid/balanceDue/
 * status this project never tracked before. Flat, not a nested wrapper
 * around Invoice, so every existing reader of invoice.subtotalAmount/
 * taxAmount/totalAmount (InvoicePanel.jsx) keeps working unchanged once
 * the frontend switches to this shape - it only gains fields.
 */
public record InvoiceWithBalance(
        UUID id,
        UUID appointmentId,
        UUID labOrderId,
        UUID dispenseRecordId,
        UUID imagingOrderId,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        String discountReason,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        Instant createdAt,
        BigDecimal amountPaid,
        BigDecimal balanceDue,
        String status
) {
    public static InvoiceWithBalance of(Invoice invoice, BigDecimal amountPaid) {
        BigDecimal balanceDue = invoice.getTotalAmount().subtract(amountPaid);
        String status = balanceDue.signum() <= 0
                ? "paid"
                : amountPaid.signum() > 0 ? "partially_paid" : "unpaid";
        return new InvoiceWithBalance(
                invoice.getId(),
                invoice.getAppointmentId(),
                invoice.getLabOrderId(),
                invoice.getDispenseRecordId(),
                invoice.getImagingOrderId(),
                invoice.getSubtotalAmount(),
                invoice.getDiscountAmount(),
                invoice.getDiscountReason(),
                invoice.getTaxAmount(),
                invoice.getTotalAmount(),
                invoice.getCreatedAt(),
                amountPaid,
                balanceDue,
                status);
    }
}
