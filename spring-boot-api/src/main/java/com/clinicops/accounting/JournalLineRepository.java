package com.clinicops.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

    List<JournalLine> findAllByJournalEntryId(UUID journalEntryId);

    /**
     * The trial balance - every account this tenant has (even one with no
     * postings yet, via the LEFT JOIN) with its raw debit/credit totals.
     * Signed balance-per-type math (asset/expense normal-debit vs.
     * liability/equity/revenue normal-credit) is computed in
     * {@link JournalController}, not here - same "compute the derived value
     * in Java" preference as Vitals.getBmi(), not worth expressing as SQL
     * CASE logic.
     */
    @Query(value = """
            SELECT a.id as accountId, a.code as code, a.name as name, a.type as type,
                   COALESCE(SUM(CASE WHEN jl.entry_type = 'debit' THEN jl.amount ELSE 0 END), 0) as debitTotal,
                   COALESCE(SUM(CASE WHEN jl.entry_type = 'credit' THEN jl.amount ELSE 0 END), 0) as creditTotal
            FROM accounts a
            LEFT JOIN journal_lines jl ON jl.account_id = a.id
            WHERE a.tenant_id = :tenantId
            GROUP BY a.id, a.code, a.name, a.type
            ORDER BY a.code
            """, nativeQuery = true)
    List<AccountBalanceView> trialBalance(@Param("tenantId") UUID tenantId);

    /**
     * Same shape as {@link #trialBalance}, but bounded to
     * {@code [start, end)} - built for com.clinicops.finance's P&L and
     * budget-vs-actual reports (phase 22), which need one month's activity,
     * not all-time totals. The period filter lives in the join's own ON
     * clause (against journal_lines.created_at directly, not a second join
     * to journal_entries) so an account with zero activity in the window
     * still gets a row via the outer LEFT JOIN, correctly aggregating to
     * zero rather than disappearing.
     */
    @Query(value = """
            SELECT a.id as accountId, a.code as code, a.name as name, a.type as type,
                   COALESCE(SUM(CASE WHEN jl.entry_type = 'debit' THEN jl.amount ELSE 0 END), 0) as debitTotal,
                   COALESCE(SUM(CASE WHEN jl.entry_type = 'credit' THEN jl.amount ELSE 0 END), 0) as creditTotal
            FROM accounts a
            LEFT JOIN journal_lines jl ON jl.account_id = a.id AND jl.created_at >= :start AND jl.created_at < :end
            WHERE a.tenant_id = :tenantId
            GROUP BY a.id, a.code, a.name, a.type
            ORDER BY a.code
            """, nativeQuery = true)
    List<AccountBalanceView> periodBalance(@Param("tenantId") UUID tenantId, @Param("start") Instant start, @Param("end") Instant end);
}
