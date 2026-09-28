package com.clinicops.finance;

import java.math.BigDecimal;

/** Partial update - only non-null fields applied. {@code email}/{@code appUserId} aren't here - fixed at creation, same as Account's own {@code code}. */
public record UpdateEmployeeRequest(
        BigDecimal salaryAmount,
        String status
) {
}
