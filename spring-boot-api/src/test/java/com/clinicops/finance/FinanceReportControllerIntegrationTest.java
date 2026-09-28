package com.clinicops.finance;

import com.clinicops.accounting.Account;
import com.clinicops.accounting.JournalEntry;
import com.clinicops.clinic.Clinic;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clinics created here never override timezone, so ClinicSettingsService
 * resolves the platform default (UTC) - "now" always falls inside
 * YearMonth.now(ZoneOffset.UTC), letting these tests exercise real
 * period-boundary filtering (this month vs. a different month) without
 * needing to hand-craft created_at timestamps.
 */
class FinanceReportControllerIntegrationTest extends AbstractIntegrationTest {

    private final YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
    private final YearMonth otherMonth = thisMonth.minusMonths(2);

    @Test
    void profitAndLossOnlyIncludesActivityInTheGivenMonth() throws Exception {
        Clinic clinic = createClinic("pl-e2e-" + UUID.randomUUID(), "P&L Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Account revenue = createAccount(clinic.getId(), "4000", "Service Revenue", "revenue");
        Account cash = createAccount(clinic.getId(), "1000", "Cash", "asset");
        Account expense = createAccount(clinic.getId(), "5000", "Salary Expense", "expense");

        JournalEntry saleEntry = createJournalEntry(clinic.getId(), "A sale", "appointment_payment", UUID.randomUUID());
        createJournalLine(clinic.getId(), saleEntry.getId(), cash.getId(), "debit", new BigDecimal("300.00"));
        createJournalLine(clinic.getId(), saleEntry.getId(), revenue.getId(), "credit", new BigDecimal("300.00"));

        JournalEntry payrollEntry = createJournalEntry(clinic.getId(), "Payroll", "payroll", UUID.randomUUID());
        createJournalLine(clinic.getId(), payrollEntry.getId(), expense.getId(), "debit", new BigDecimal("120.00"));
        createJournalLine(clinic.getId(), payrollEntry.getId(), cash.getId(), "credit", new BigDecimal("120.00"));

        mockMvc.perform(get("/api/clinic/profit-and-loss").with(asAccountant("acct", orgAlias))
                        .param("year", String.valueOf(thisMonth.getYear())).param("month", String.valueOf(thisMonth.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(300.00))
                .andExpect(jsonPath("$.totalExpense").value(120.00))
                .andExpect(jsonPath("$.netIncome").value(180.00));

        mockMvc.perform(get("/api/clinic/profit-and-loss").with(asAccountant("acct", orgAlias))
                        .param("year", String.valueOf(otherMonth.getYear())).param("month", String.valueOf(otherMonth.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(0))
                .andExpect(jsonPath("$.totalExpense").value(0))
                .andExpect(jsonPath("$.netIncome").value(0));
    }

    @Test
    void budgetVsActualComputesVarianceAndOmitsUnbudgetedUntouchedAccounts() throws Exception {
        Clinic clinic = createClinic("bva-e2e-" + UUID.randomUUID(), "Budget vs Actual Clinic");
        String orgAlias = clinic.getKeycloakOrgId();
        Account expense = createAccount(clinic.getId(), "5000", "Salary Expense", "expense");
        Account untouched = createAccount(clinic.getId(), "6000", "Untouched Account", "expense");
        createBudget(clinic.getId(), expense.getId(), thisMonth.getYear(), thisMonth.getMonthValue(), new BigDecimal("1000.00"));

        JournalEntry entry = createJournalEntry(clinic.getId(), "Payroll", "payroll", UUID.randomUUID());
        createJournalLine(clinic.getId(), entry.getId(), expense.getId(), "debit", new BigDecimal("1200.00"));

        mockMvc.perform(get("/api/clinic/budget-vs-actual").with(asAccountant("acct", orgAlias))
                        .param("year", String.valueOf(thisMonth.getYear())).param("month", String.valueOf(thisMonth.getMonthValue())))
                .andExpect(status().isOk())
                // Exactly one row (the untouched, unbudgeted "6000" account never appears).
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value(expense.getId().toString()))
                .andExpect(jsonPath("$[?(@.accountId=='" + untouched.getId() + "')]").doesNotExist())
                .andExpect(jsonPath("$[0].code").value("5000"))
                .andExpect(jsonPath("$[0].budgetAmount").value(1000.00))
                .andExpect(jsonPath("$[0].actualAmount").value(1200.00))
                .andExpect(jsonPath("$[0].variance").value(200.00));
    }

    @Test
    void onlyAccountantAndClinicAdminCanReachTheseReports() throws Exception {
        Clinic clinic = createClinic("finance-report-role-" + UUID.randomUUID(), "Role Gate Clinic");
        mockMvc.perform(get("/api/clinic/profit-and-loss").with(asProvider("prov", clinic.getKeycloakOrgId()))
                        .param("year", "2026").param("month", "9"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/clinic/budget-vs-actual").with(asFrontDesk("fd", clinic.getKeycloakOrgId()))
                        .param("year", "2026").param("month", "9"))
                .andExpect(status().isForbidden());
    }
}
