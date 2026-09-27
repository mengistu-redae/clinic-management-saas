package com.clinicops.accounting;

import java.math.BigDecimal;
import java.util.UUID;

/** Raw per-account debit/credit totals backing the trial balance - {@link JournalController} computes the signed balance from these plus the account's own type. */
public interface AccountBalanceView {

    UUID getAccountId();

    String getCode();

    String getName();

    String getType();

    BigDecimal getDebitTotal();

    BigDecimal getCreditTotal();
}
