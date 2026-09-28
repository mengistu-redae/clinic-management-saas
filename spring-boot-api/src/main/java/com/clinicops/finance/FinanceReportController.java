package com.clinicops.finance;

import com.clinicops.accounting.AccountBalanceView;
import com.clinicops.accounting.BalanceMath;
import com.clinicops.accounting.JournalLineRepository;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only reporting on top of {@link JournalLineRepository}'s own
 * period-bounded balance query - no dedicated service bean, same
 * "controller composes repositories directly" precedent phase 5/19's own
 * plain-CRUD/analytics endpoints already set, since there's no
 * cross-cutting business logic here beyond composition. Month boundaries
 * resolve through the clinic's own timezone
 * (ClinicSettingsService.resolveTimezone), the same shared seam every
 * other day/month-boundary call site in this app already goes through
 * (phase 18) - deliberately not left on a hardcoded UTC reading.
 */
@RestController
public class FinanceReportController {

    private final JournalLineRepository journalLineRepository;
    private final BudgetRepository budgetRepository;
    private final ClinicSettingsService clinicSettingsService;

    public FinanceReportController(
            JournalLineRepository journalLineRepository,
            BudgetRepository budgetRepository,
            ClinicSettingsService clinicSettingsService) {
        this.journalLineRepository = journalLineRepository;
        this.budgetRepository = budgetRepository;
        this.clinicSettingsService = clinicSettingsService;
    }

    @GetMapping("/api/clinic/profit-and-loss")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public ProfitAndLossResponse profitAndLoss(@RequestParam int year, @RequestParam int month) {
        UUID tenantId = TenantContext.require();
        List<AccountBalanceView> balances = journalLineRepository.periodBalance(tenantId, monthStart(tenantId, year, month), monthEnd(tenantId, year, month));

        List<ProfitAndLossLine> revenueLines = balances.stream()
                .filter(v -> "revenue".equals(v.getType()))
                .map(v -> new ProfitAndLossLine(v.getAccountId(), v.getCode(), v.getName(),
                        BalanceMath.signedBalance(v.getType(), v.getDebitTotal(), v.getCreditTotal())))
                .toList();
        List<ProfitAndLossLine> expenseLines = balances.stream()
                .filter(v -> "expense".equals(v.getType()))
                .map(v -> new ProfitAndLossLine(v.getAccountId(), v.getCode(), v.getName(),
                        BalanceMath.signedBalance(v.getType(), v.getDebitTotal(), v.getCreditTotal())))
                .toList();

        BigDecimal totalRevenue = sum(revenueLines);
        BigDecimal totalExpense = sum(expenseLines);
        return new ProfitAndLossResponse(year, month, revenueLines, expenseLines, totalRevenue, totalExpense, totalRevenue.subtract(totalExpense));
    }

    @GetMapping("/api/clinic/budget-vs-actual")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<BudgetVsActualRow> budgetVsActual(@RequestParam int year, @RequestParam int month) {
        UUID tenantId = TenantContext.require();
        List<AccountBalanceView> balances = journalLineRepository.periodBalance(tenantId, monthStart(tenantId, year, month), monthEnd(tenantId, year, month));
        Map<UUID, BigDecimal> budgetsByAccount = new HashMap<>();
        for (Budget budget : budgetRepository.findAllByTenantIdAndYearAndMonth(tenantId, year, month)) {
            budgetsByAccount.put(budget.getAccountId(), budget.getAmount());
        }

        return balances.stream()
                // Only accounts with either a budget set or real activity this period - an
                // untouched, unbudgeted account is just noise in this report.
                .filter(v -> budgetsByAccount.containsKey(v.getAccountId())
                        || v.getDebitTotal().signum() != 0 || v.getCreditTotal().signum() != 0)
                .map(v -> {
                    BigDecimal actual = BalanceMath.signedBalance(v.getType(), v.getDebitTotal(), v.getCreditTotal());
                    BigDecimal budgeted = budgetsByAccount.get(v.getAccountId());
                    BigDecimal variance = budgeted == null ? null : actual.subtract(budgeted);
                    return new BudgetVsActualRow(v.getAccountId(), v.getCode(), v.getName(), v.getType(), budgeted, actual, variance);
                })
                .toList();
    }

    private BigDecimal sum(List<ProfitAndLossLine> lines) {
        return lines.stream().map(ProfitAndLossLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Instant monthStart(UUID tenantId, int year, int month) {
        ZoneId zone = clinicSettingsService.resolveTimezone(tenantId);
        return YearMonth.of(year, month).atDay(1).atStartOfDay(zone).toInstant();
    }

    private Instant monthEnd(UUID tenantId, int year, int month) {
        ZoneId zone = clinicSettingsService.resolveTimezone(tenantId);
        return YearMonth.of(year, month).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }
}
