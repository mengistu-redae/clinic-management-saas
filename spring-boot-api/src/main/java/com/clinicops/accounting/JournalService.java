package com.clinicops.accounting;

import com.clinicops.payment.Payment;
import com.clinicops.payment.Refund;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Auto-posts a balanced two-line journal entry whenever an existing
 * {@link Payment}/{@link Refund} happens - the ledger picks up real cash
 * events, it doesn't create new ones (the pinned phase-21 decision). Called
 * explicitly from each of the five existing Payment/Refund-creating call
 * sites (PaymentService, RefundService, and the three fee_auto_charged
 * shortcuts in Cancellation/Reschedule/LabOrderCancellationService) - same
 * "explicit call sites, not AOP/an annotation" convention
 * PhiAccessAuditService already established, chosen for the same reason:
 * an event listener would post for every Payment/Refund uniformly, but
 * that's exactly what's wanted here (every payment or refund, gateway
 * -routed or fee_auto_charged alike, is a real cash event) - explicit
 * calls just make that visible at each call site rather than implicit.
 *
 * <p>Deliberately does not split tax out of a payment linked to an
 * invoice - v1 posts the full amount straight to Service Revenue,
 * matching this app's own "keep minimal in v1" convention elsewhere
 * (ICD-10 free text, Prescription's route allow-list). A tax-aware
 * sub-ledger is a later refinement if ever needed, not built speculatively
 * now.</p>
 */
@Service
public class JournalService {

    private static final String CASH_CODE = "1000";
    private static final String REVENUE_CODE = "4000";
    private static final String REFUNDS_CODE = "4900";
    private static final String SALARY_EXPENSE_CODE = "5000";

    private final AccountRepository accountRepository;
    private final AccountSeedingService accountSeedingService;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    public JournalService(
            AccountRepository accountRepository,
            AccountSeedingService accountSeedingService,
            JournalEntryRepository journalEntryRepository,
            JournalLineRepository journalLineRepository) {
        this.accountRepository = accountRepository;
        this.accountSeedingService = accountSeedingService;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
    }

    /** Debit Cash / Credit Service Revenue for the full payment amount - covers every channel/method, including a fee_auto_charged row (never gateway-routed, but still a real cash event). */
    @Transactional
    public void postForPayment(Payment payment) {
        UUID tenantId = payment.getTenantId();
        accountSeedingService.ensureSeeded(tenantId);
        Account cash = requireAccount(tenantId, CASH_CODE);
        Account revenue = requireAccount(tenantId, REVENUE_CODE);
        String sourceType = payment.getAppointmentId() != null ? "appointment_payment" : "lab_order_payment";
        post(tenantId, "Payment received (" + payment.getMethod() + ")", sourceType, payment.getId(),
                payment.getRecordedBy(), cash, revenue, payment.getAmount());
    }

    /** Debit Refunds & Allowances / Credit Cash for the refund amount - regardless of whether the underlying payment was ever gateway-charged. */
    @Transactional
    public void postForRefund(Refund refund) {
        UUID tenantId = refund.getTenantId();
        accountSeedingService.ensureSeeded(tenantId);
        Account cash = requireAccount(tenantId, CASH_CODE);
        Account refunds = requireAccount(tenantId, REFUNDS_CODE);
        post(tenantId, "Refund issued", "refund", refund.getId(),
                refund.getRefundedBy(), refunds, cash, refund.getAmount());
    }

    /**
     * Debit Salary Expense / Credit Cash for one employee's pay for one
     * payroll run - called once per employee by
     * {@code com.clinicops.finance.PayrollService}, not once for the whole
     * run, so a single employee's pay can be traced (or reversed) on its
     * own. Returns the posted entry so the caller can link its own
     * {@code payroll_payments.journal_entry_id} back to it.
     */
    @Transactional
    public JournalEntry postForPayroll(UUID tenantId, UUID employeeId, BigDecimal amount, String description, UUID postedBy) {
        accountSeedingService.ensureSeeded(tenantId);
        Account salaryExpense = requireAccount(tenantId, SALARY_EXPENSE_CODE);
        Account cash = requireAccount(tenantId, CASH_CODE);
        return post(tenantId, description, "payroll", employeeId, postedBy, salaryExpense, cash, amount);
    }

    private Account requireAccount(UUID tenantId, String code) {
        return accountRepository.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing chart-of-accounts entry " + code + " for tenant " + tenantId
                                + " - it was either never seeded or was renamed/removed after seeding"));
    }

    private JournalEntry post(UUID tenantId, String description, String sourceType, UUID sourceId, UUID postedBy,
                       Account debitAccount, Account creditAccount, BigDecimal amount) {
        JournalEntry entry = new JournalEntry();
        entry.setTenantId(tenantId);
        entry.setDescription(description);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setPostedBy(postedBy);
        entry = journalEntryRepository.save(entry);

        JournalLine debit = new JournalLine();
        debit.setTenantId(tenantId);
        debit.setJournalEntryId(entry.getId());
        debit.setAccountId(debitAccount.getId());
        debit.setAmount(amount);
        debit.setEntryType("debit");
        journalLineRepository.save(debit);

        JournalLine credit = new JournalLine();
        credit.setTenantId(tenantId);
        credit.setJournalEntryId(entry.getId());
        credit.setAccountId(creditAccount.getId());
        credit.setAmount(amount);
        credit.setEntryType("credit");
        journalLineRepository.save(credit);

        return entry;
    }
}
