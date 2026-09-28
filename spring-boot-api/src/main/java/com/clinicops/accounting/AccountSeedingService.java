package com.clinicops.accounting;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Lazy, idempotent per-tenant seeding - same "generate on first access, not
 * at creation time" reasoning as {@code SlotGenerationService}, chosen
 * because clinics already existed before this phase shipped and a
 * migration-time backfill can't reach clinics created after it ran anyway.
 * Seeds exactly the accounts {@link JournalService}'s own auto-posting
 * logic targets - not a full generic chart of accounts - so there's never
 * a seeded-but-unused row; an accountant adds anything more specific
 * through {@link AccountController}.
 */
@Service
public class AccountSeedingService {

    private record StarterAccount(String code, String name, String type) {
    }

    private static final List<StarterAccount> STARTER_ACCOUNTS = List.of(
            new StarterAccount("1000", "Cash", "asset"),
            new StarterAccount("4000", "Service Revenue", "revenue"),
            new StarterAccount("4900", "Refunds & Allowances", "revenue"),
            new StarterAccount("5000", "Salary Expense", "expense"));

    private final AccountRepository accountRepository;

    public AccountSeedingService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Checks each starter account individually rather than short-circuiting
     * on "this tenant has any account at all" - a real bug fixed while
     * adding Salary Expense in phase 22: a tenant that already had its
     * original three accounts seeded (phase 21) would otherwise never pick
     * up a starter account added by a later phase, since the old
     * fast-path treated "has ≥1 account" as "fully seeded." The per
     * -account check below is what actually makes this idempotent AND
     * extensible - a few extra existence checks per call is a fine trade
     * for that.
     */
    public void ensureSeeded(UUID tenantId) {
        for (StarterAccount starter : STARTER_ACCOUNTS) {
            if (accountRepository.findByTenantIdAndCode(tenantId, starter.code()).isPresent()) {
                continue;
            }
            Account account = new Account();
            account.setTenantId(tenantId);
            account.setCode(starter.code());
            account.setName(starter.name());
            account.setType(starter.type());
            try {
                accountRepository.save(account);
            } catch (DataIntegrityViolationException e) {
                // A concurrent request seeded this same (tenant, code) row
                // first - the UNIQUE(tenant_id, code) constraint is the real
                // race-breaker, same idiom as SlotGenerationService's own
                // saveSlotIfAbsent.
            }
        }
    }
}
