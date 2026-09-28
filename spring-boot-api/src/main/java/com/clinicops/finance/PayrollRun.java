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
 * One row per "run payroll for this clinic, this month" action -
 * {@code UNIQUE(tenant_id, year, month)} makes a re-run of the same month
 * idempotent-by-rejection (409), not idempotent-by-no-op, matching
 * Invoice's own "immutable once issued, a repeat 409s" convention rather
 * than Encounter's upsert-in-place - re-running payroll for a month
 * that's already been paid is a genuine error, not a safe retry.
 */
@Entity
@Table(name = "payroll_runs")
@Getter
@Setter
public class PayrollRun extends BaseTenantEntity {

    @Column(nullable = false)
    private int year;

    @Column(nullable = false)
    private int month;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "run_by")
    private UUID runBy;
}
