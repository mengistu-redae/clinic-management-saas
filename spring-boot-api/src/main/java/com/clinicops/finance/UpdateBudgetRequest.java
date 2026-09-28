package com.clinicops.finance;

import java.math.BigDecimal;

/** Partial update - only {@code amount} is editable; account/year/month are fixed at creation (change those by deleting and recreating, same as LabRate's own testCode precedent). */
public record UpdateBudgetRequest(
        BigDecimal amount
) {
}
