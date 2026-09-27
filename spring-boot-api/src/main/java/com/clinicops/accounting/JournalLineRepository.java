package com.clinicops.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
