import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useAccounts,
  useBudgets,
  useCreateBudget,
  useUpdateBudget,
  useDeleteBudget,
  useProfitAndLoss,
  useBudgetVsActual,
} from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Card from '../../components/Card.jsx';
import Tabs from '../../components/Tabs.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency } from '../../lib/format.js';

const now = new Date();

/**
 * One period selector drives three things at once: the budget list below
 * (filtered to that year/month), and the two reports (P&L, budget-vs
 * -actual) further down - all three are genuinely the same "for this
 * month" question, so one shared control instead of three independent
 * ones. Budgets get a real hard delete (a missing row just means "no
 * target set," same well-defined-fallback reasoning FeePolicy/LabRate
 * already use).
 */
export default function AccountantBudgets() {
  const { t } = useTranslation();
  const [period, setPeriod] = useState({ year: now.getFullYear(), month: now.getMonth() + 1 });
  const [activeTab, setActiveTab] = useState('budgets');

  const accounts = useAccounts(true, 'active');
  const budgets = useBudgets(true, period.year, period.month);
  const createBudget = useCreateBudget();
  const pl = useProfitAndLoss(true, period.year, period.month);
  const budgetVsActual = useBudgetVsActual(true, period.year, period.month);

  const [form, setForm] = useState({ accountId: '', amount: '' });
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const amount = Number(form.amount);
    if (!form.accountId || !form.amount || amount <= 0) {
      setFormError(t('accountantPage.errorBudgetFieldsRequired'));
      return;
    }
    try {
      await createBudget.mutateAsync({ accountId: form.accountId, year: period.year, month: period.month, amount });
      setForm({ accountId: '', amount: '' });
    } catch (err) {
      setFormError(err.message || t('accountantPage.errorCreateBudget'));
    }
  }

  const budgetColumns = [
    { key: 'account', header: t('accountantPage.account'), accessor: (b) => accountLabel(accounts.data, b.accountId), sortable: true, className: 'font-semibold' },
    { key: 'amount', header: t('accountantPage.budgetAmount'), accessor: (b) => b.amount, sortable: true, render: (b) => formatCurrency(b.amount) },
  ];

  const bvaColumns = [
    { key: 'code', header: t('accountantPage.code'), accessor: (r) => r.code, sortable: true, className: 'font-mono font-semibold' },
    { key: 'name', header: t('common.name'), accessor: (r) => r.name, sortable: true },
    { key: 'budgetAmount', header: t('accountantPage.budgetAmount'), accessor: (r) => r.budgetAmount ?? -1, sortable: true, render: (r) => (r.budgetAmount === null ? '—' : formatCurrency(r.budgetAmount)) },
    { key: 'actualAmount', header: t('accountantPage.actualAmount'), accessor: (r) => r.actualAmount, sortable: true, render: (r) => formatCurrency(r.actualAmount) },
    {
      key: 'variance',
      header: t('accountantPage.variance'),
      accessor: (r) => r.variance ?? 0,
      sortable: true,
      render: (r) => (r.variance === null ? '—' : <span className={r.variance > 0 ? 'text-danger' : 'text-success'}>{formatCurrency(r.variance)}</span>),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.accountant.budgets')} description={t('accountantPage.budgetsDescription')} />

      <Card className="mb-6 flex flex-wrap items-end gap-3">
        <Field label={t('accountantPage.year')}>
          <input type="number" min="2000" max={now.getFullYear() + 1} value={period.year} onChange={(e) => setPeriod({ ...period, year: Number(e.target.value) })} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('accountantPage.month')}>
          <select value={period.month} onChange={(e) => setPeriod({ ...period, month: Number(e.target.value) })} className={`${inputClass} w-24`}>
            {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => <option key={m} value={m}>{String(m).padStart(2, '0')}</option>)}
          </select>
        </Field>
      </Card>

      <Tabs
        tabs={[
          { key: 'budgets', label: t('accountantPage.budgetsForPeriod') },
          { key: 'pl', label: t('accountantPage.profitAndLoss') },
          { key: 'bva', label: t('accountantPage.budgetVsActual') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      />

      {activeTab === 'budgets' && (
        <Card>
          <form onSubmit={handleCreate} className="mb-4 flex flex-wrap items-end gap-3">
            <Field label={t('accountantPage.account')}>
              <select value={form.accountId} onChange={(e) => setForm({ ...form, accountId: e.target.value })} className={`${inputClass} w-56`}>
                <option value="">{t('booking.select')}</option>
                {(accounts.data || []).map((a) => <option key={a.id} value={a.id}>{a.code} {a.name}</option>)}
              </select>
            </Field>
            <Field label={t('accountantPage.budgetAmount')}>
              <input type="number" min="0" step="0.01" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className={`${inputClass} w-32`} />
            </Field>
            <Button type="submit" variant="accent" disabled={createBudget.isPending}>
              {createBudget.isPending ? t('common.adding') : t('accountantPage.addBudget')}
            </Button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

          <DataTable
            columns={budgetColumns}
            rows={budgets.data || []}
            rowKey="id"
            defaultSortKey="account"
            renderExpanded={(budget) => <BudgetEditPanel budget={budget} />}
            isLoading={budgets.isLoading}
            error={budgets.isError ? budgets.error : null}
            onRetry={budgets.refetch}
            emptyTitle={t('accountantPage.emptyBudgetsTitle')}
            emptyDescription={t('accountantPage.emptyBudgetsDescription')}
          />
        </Card>
      )}

      {activeTab === 'pl' && (
        <Card>
          {pl.isLoading && <Skeleton className="h-24 w-full" />}
          {pl.isError && <ErrorBanner message={pl.error?.message} onRetry={pl.refetch} />}
          {!pl.isLoading && !pl.isError && pl.data && (
            <div className="grid gap-4 sm:grid-cols-3">
              <SummaryStat label={t('accountantPage.totalRevenue')} value={formatCurrency(pl.data.totalRevenue)} />
              <SummaryStat label={t('accountantPage.totalExpense')} value={formatCurrency(pl.data.totalExpense)} />
              <SummaryStat label={t('accountantPage.netIncome')} value={formatCurrency(pl.data.netIncome)} />
            </div>
          )}
        </Card>
      )}

      {activeTab === 'bva' && (
        <Card>
          <DataTable
            columns={bvaColumns}
            rows={budgetVsActual.data || []}
            rowKey="accountId"
            defaultSortKey="code"
            isLoading={budgetVsActual.isLoading}
            error={budgetVsActual.isError ? budgetVsActual.error : null}
            onRetry={budgetVsActual.refetch}
            emptyTitle={t('accountantPage.emptyBudgetVsActualTitle')}
            emptyDescription={t('accountantPage.emptyBudgetVsActualDescription')}
          />
        </Card>
      )}
    </PageContainer>
  );
}

function accountLabel(accounts, accountId) {
  const account = (accounts || []).find((a) => a.id === accountId);
  return account ? `${account.code} ${account.name}` : accountId;
}

function BudgetEditPanel({ budget }) {
  const { t } = useTranslation();
  const updateBudget = useUpdateBudget(budget.id);
  const deleteBudget = useDeleteBudget(budget.id);
  const [amount, setAmount] = useState(String(budget.amount));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateBudget.mutateAsync({ amount: Number(amount) });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deleteBudget.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('accountantPage.errorDeleteBudget'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('accountantPage.budgetAmount')}>
          <input type="number" min="0" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} className={`${inputClass} w-32`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateBudget.isPending}>
          {t('common.save')}
        </Button>
        <button type="button" onClick={handleDelete} className="text-sm text-danger hover:underline">
          {t('common.delete')}
        </button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}

function SummaryStat({ label, value }) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</p>
      <p className="mt-1 text-xl font-bold tabular-nums text-ink">{value}</p>
    </div>
  );
}
