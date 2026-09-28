package com.clinicops.finance;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A per-account, per-month target - {@code UNIQUE(tenant_id, account_id,
 * year, month)} so setting a budget for the same account/period twice is
 * a real conflict (409, update instead), not a silent second row.
 * References {@code com.clinicops.accounting.Account} directly - finance
 * is explicitly "built on top of phase 21's ledger," not a parallel
 * account concept of its own.
 */
@Entity
@Table(name = "budgets")
@Getter
@Setter
public class Budget extends BaseTenantEntity {

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int month;

    @Column(nullable = false)
    private BigDecimal amount;
}
