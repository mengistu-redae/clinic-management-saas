import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useProfitAndLoss, useTrialBalance, useEmployees } from '../../api/queries.js';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatCurrency } from '../../lib/format.js';

const CASH_ACCOUNT_CODE = '1000';

/**
 * The accountant's landing page - a snapshot of the current calendar
 * month's P&L plus the Cash account's own running balance, and links into
 * the rest of this module. No charts here, unlike the clinic-admin
 * analytics dashboard - this phase's own scope stayed to plain numbers
 * (see FinanceReportController's javadoc), not a data-visualization pass.
 */
export default function AccountantDashboard() {
  const { t } = useTranslation();
  const now = new Date();
  const year = now.getUTCFullYear();
  const month = now.getUTCMonth() + 1;

  const pl = useProfitAndLoss(true, year, month);
  const trialBalance = useTrialBalance(true);
  const employees = useEmployees(true, 'active');

  const queries = [pl, trialBalance, employees];
  const isLoading = queries.some((q) => q.isLoading);
  const isError = queries.some((q) => q.isError);

  if (isError) {
    return (
      <PageContainer width="lg">
        <ErrorBanner
          message={queries.find((q) => q.isError)?.error?.message}
          onRetry={() => queries.forEach((q) => q.refetch())}
        />
      </PageContainer>
    );
  }
  if (isLoading) {
    return (
      <PageContainer width="lg">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      </PageContainer>
    );
  }

  const cashBalance = (trialBalance.data || []).find((a) => a.code === CASH_ACCOUNT_CODE)?.balance;

  return (
    <PageContainer width="lg">
      <PageHeader title={t('accountantPage.dashboardTitle')} description={t('accountantPage.dashboardSubtitle', { period: `${year}-${String(month).padStart(2, '0')}` })} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label={t('accountantPage.cashBalance')} value={formatCurrency(cashBalance ?? 0)} mono />
        <StatCard label={t('accountantPage.revenueThisMonth')} value={formatCurrency(pl.data.totalRevenue)} mono />
        <StatCard label={t('accountantPage.expenseThisMonth')} value={formatCurrency(pl.data.totalExpense)} mono />
        <StatCard label={t('accountantPage.netIncomeThisMonth')} value={formatCurrency(pl.data.netIncome)} mono />
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Link to="/accountant/accounts" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.accountant.accounts')}
        </Link>
        <Link to="/accountant/journal" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.accountant.journal')}
        </Link>
        <Link to="/accountant/employees" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('accountantPage.employeesOnPayroll', { count: (employees.data || []).length })}
        </Link>
        <Link to="/accountant/payroll" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.accountant.payroll')}
        </Link>
        <Link to="/accountant/budgets" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.accountant.budgets')}
        </Link>
      </div>
    </PageContainer>
  );
}
