package com.clinicops.accounting;

import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read-only - the journal itself is only ever written by {@link JournalService}'s own auto-posting, never through a request body here. */
@RestController
public class JournalController {

    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    public JournalController(JournalEntryRepository journalEntryRepository, JournalLineRepository journalLineRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
    }

    @GetMapping("/api/clinic/journal-entries")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<JournalEntryWithLines> journalEntries() {
        UUID tenantId = TenantContext.require();
        return journalEntryRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(entry -> new JournalEntryWithLines(entry, journalLineRepository.findAllByJournalEntryId(entry.getId())))
                .toList();
    }

    @GetMapping("/api/clinic/trial-balance")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<TrialBalanceRow> trialBalance() {
        UUID tenantId = TenantContext.require();
        return journalLineRepository.trialBalance(tenantId).stream()
                .map(this::toRow)
                .toList();
    }

    private TrialBalanceRow toRow(AccountBalanceView view) {
        BigDecimal balance = BalanceMath.signedBalance(view.getType(), view.getDebitTotal(), view.getCreditTotal());
        return new TrialBalanceRow(view.getAccountId(), view.getCode(), view.getName(), view.getType(), view.getDebitTotal(), view.getCreditTotal(), balance);
    }
}
