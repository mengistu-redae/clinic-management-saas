package com.clinicops.finance;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code budgetAmount} is null when no {@link Budget} row exists for this account/period - the account still shows up if it has real activity that period, so an unbudgeted overspend is never hidden. */
public record BudgetVsActualRow(
        UUID accountId,
        String code,
        String name,
        String type,
        BigDecimal budgetAmount,
        BigDecimal actualAmount,
        BigDecimal variance
) {
}
