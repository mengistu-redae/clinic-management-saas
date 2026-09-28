import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePayrollRuns, usePayrollRun, useRunPayroll, useEmployees } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const now = new Date();

/**
 * Run payroll for a given month - POST /api/clinic/payroll-runs, one
 * balanced journal entry per active employee (see JournalService
 * .postForPayroll). Re-running an already-paid month 409s, matching
 * Invoice's own "immutable once issued" convention. The history list below
 * only carries each run's own header (GET /api/clinic/payroll-runs) - its
 * per-employee payments are fetched lazily per row on expand
 * (GET /api/clinic/payroll-runs/{id}), same "each row owns its own query"
 * shape PaymentsPanel.jsx already established (phase 16).
 */
export default function AccountantPayroll() {
  const { t } = useTranslation();
  const { data: runs, isLoading, isError, error, refetch } = usePayrollRuns(true);
  const employees = useEmployees(true);
  const runPayroll = useRunPayroll();

  const [form, setForm] = useState({ year: now.getFullYear(), month: now.getMonth() + 1 });
  const [formError, setFormError] = useState(null);
  const [lastRun, setLastRun] = useState(null);

  const employeeNameById = useMemo(
    () => Object.fromEntries((employees.data || []).map((e) => [e.id, e.fullName || e.email])),
    [employees.data],
  );

  async function handleRun(event) {
    event.preventDefault();
    setFormError(null);
    setLastRun(null);
    try {
      const result = await runPayroll.mutateAsync({ year: Number(form.year), month: Number(form.month) });
      setLastRun(result);
    } catch (err) {
      setFormError(err.message || t('accountantPage.errorRunPayroll'));
    }
  }

  const columns = [
    { key: 'period', header: t('accountantPage.period'), accessor: (r) => `${r.year}-${String(r.month).padStart(2, '0')}`, sortable: true, className: 'font-semibold' },
    { key: 'totalAmount', header: t('accountantPage.totalAmount'), accessor: (r) => r.totalAmount, sortable: true, render: (r) => formatCurrency(r.totalAmount) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.accountant.payroll')} description={t('accountantPage.payrollDescription')} />

      <form onSubmit={handleRun} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('accountantPage.year')}>
          <input type="number" min="2000" value={form.year} onChange={(e) => setForm({ ...form, year: e.target.value })} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('accountantPage.month')}>
          <select value={form.month} onChange={(e) => setForm({ ...form, month: e.target.value })} className={`${inputClass} w-24`}>
            {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => <option key={m} value={m}>{String(m).padStart(2, '0')}</option>)}
          </select>
        </Field>
        <Button type="submit" variant="accent" disabled={runPayroll.isPending}>
          {runPayroll.isPending ? t('accountantPage.runningPayroll') : t('accountantPage.runPayroll')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
      {lastRun && (
        <div className="mb-6 flex items-center justify-between rounded-lg border border-success/30 bg-success-light px-4 py-2.5 text-sm text-success">
          <span>{t('accountantPage.payrollRunConfirmed', { amount: formatCurrency(lastRun.run.totalAmount), count: lastRun.payments.length })}</span>
          <button type="button" onClick={() => setLastRun(null)} className="font-semibold hover:underline">
            {t('clinicsPage.dismiss')}
          </button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={runs || []}
        rowKey="id"
        defaultSortKey="period"
        defaultSortDir="desc"
        renderExpanded={(run) => <PayrollRunPayments runId={run.id} employeeNameById={employeeNameById} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('accountantPage.emptyPayrollTitle')}
        emptyDescription={t('accountantPage.emptyPayrollDescription')}
      />
    </PageContainer>
  );
}

function PayrollRunPayments({ runId, employeeNameById }) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = usePayrollRun(runId);

  if (isLoading) return <Skeleton className="h-16 w-full" />;
  if (isError) return <ErrorBanner message={error?.message} onRetry={refetch} />;

  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-ink-muted">
          <th className="py-1 pr-4 font-semibold">{t('common.name')}</th>
          <th className="py-1 font-semibold">{t('accountantPage.amount')}</th>
        </tr>
      </thead>
      <tbody>
        {data.payments.map((payment) => (
          <tr key={payment.id} className="border-t border-slate-100">
            <td className="py-1.5 pr-4">{employeeNameById[payment.employeeId] || payment.employeeId}</td>
            <td className="py-1.5 font-mono">{formatCurrency(payment.amount)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
