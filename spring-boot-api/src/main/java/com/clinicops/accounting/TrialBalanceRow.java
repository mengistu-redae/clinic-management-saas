package com.clinicops.accounting;

import java.math.BigDecimal;
import java.util.UUID;

/** One account's trial-balance row - balance is signed per its normal-balance side (positive for asset/expense's own debit-normal reading, positive for liability/equity/revenue's own credit-normal reading). */
public record TrialBalanceRow(
        UUID accountId,
        String code,
        String name,
        String type,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        BigDecimal balance
) {
}
