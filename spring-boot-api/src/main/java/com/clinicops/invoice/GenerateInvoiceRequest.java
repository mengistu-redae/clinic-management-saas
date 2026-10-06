package com.clinicops.invoice;

import java.math.BigDecimal;

/**
 * All fields optional - every existing caller posting no body at all keeps
 * generating an undiscounted invoice exactly as before phase 46. At most
 * one of discountPercent/discountAmount may be given - enforced in
 * InvoiceService.build, the same "a validation annotation alone can't
 * express this" shape ReferralService's own internal-xor-external check
 * already uses.
 */
public record GenerateInvoiceRequest(BigDecimal discountPercent, BigDecimal discountAmount, String discountReason) {
}
