package com.clinicops.accounting;

import java.math.BigDecimal;
import java.util.Set;

/**
 * The one signed-balance computation every account-balance view in this
 * package (and com.clinicops.finance, built on top of it) needs: asset/
 * expense accounts are debit-normal, liability/equity/revenue accounts are
 * credit-normal. Shared here rather than duplicated between
 * {@link JournalController}'s trial balance and finance's P&L/budget
 * -vs-actual reports, since both compute the exact same thing from the
 * exact same {@link AccountBalanceView} shape.
 */
public final class BalanceMath {

    private static final Set<String> DEBIT_NORMAL_TYPES = Set.of("asset", "expense");

    private BalanceMath() {
    }

    public static BigDecimal signedBalance(String accountType, BigDecimal debitTotal, BigDecimal creditTotal) {
        return DEBIT_NORMAL_TYPES.contains(accountType) ? debitTotal.subtract(creditTotal) : creditTotal.subtract(debitTotal);
    }
}
