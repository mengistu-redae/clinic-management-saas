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
 * One line item per employee paid in a {@link PayrollRun} - own table, no
 * JPA relation, same "plain UUID FK + explicit repository queries"
 * convention as JournalLine/LabOrderTest. {@code journalEntryId} links
 * each payslip back to the specific balanced entry
 * {@code JournalService.postForPayroll} posted for it - one entry per
 * employee per run, not one entry for the whole run, so a single
 * employee's pay can be traced on its own.
 */
@Entity
@Table(name = "payroll_payments")
@Getter
@Setter
public class PayrollPayment extends BaseTenantEntity {

    @Column(name = "payroll_run_id", nullable = false)
    private UUID payrollRunId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;
}
