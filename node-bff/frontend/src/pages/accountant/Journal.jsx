import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAccounts, useJournalEntries, useTrialBalance } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Card from '../../components/Card.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * Read-only - GET /api/clinic/journal-entries and GET
 * /api/clinic/trial-balance, JournalController's own two endpoints. The
 * ledger is only ever written by JournalService's auto-posting (Payment/
 * Refund/payroll), never through a form here - matches this backend having
 * no create/update endpoint on either resource at all.
 */
export default function AccountantJournal() {
  const { t } = useTranslation();
  const accounts = useAccounts(true);
  const trialBalance = useTrialBalance(true);
  const entries = useJournalEntries(true);

  // This ledger grows forever (every payment/refund/payroll run posts a new
  // entry) with no backend date-range param on GET /api/clinic/journal-entries
  // - a plain client-side period/source-type filter over the already-fetched
  // list, same spirit as the year/month options being derived from the real
  // data present rather than a hardcoded guess (2026-10-01 search/filter audit).
  const [yearFilter, setYearFilter] = useState('');
  const [monthFilter, setMonthFilter] = useState('');
  const [sourceTypeFilter, setSourceTypeFilter] = useState('');

  const accountById = useMemo(
    () => Object.fromEntries((accounts.data || []).map((a) => [a.id, a])),
    [accounts.data],
  );

  const years = useMemo(
    () => [...new Set((entries.data || []).map((e) => new Date(e.entry.createdAt).getFullYear()))].sort((a, b) => b - a),
    [entries.data],
  );
  const sourceTypes = useMemo(
    () => [...new Set((entries.data || []).map((e) => e.entry.sourceType))].sort(),
    [entries.data],
  );

  const visibleEntries = (entries.data || []).filter((e) => {
    const date = new Date(e.entry.createdAt);
    if (yearFilter && date.getFullYear() !== Number(yearFilter)) return false;
    if (monthFilter && date.getMonth() + 1 !== Number(monthFilter)) return false;
    if (sourceTypeFilter && e.entry.sourceType !== sourceTypeFilter) return false;
    return true;
  });

  function accountLabel(accountId) {
    const account = accountById[accountId];
    if (account) return `${account.code} ${account.name}`;
    // accounts/entries fetch concurrently - a row expanded before `accounts`
    // finishes loading would otherwise show a raw, meaningless UUID.
    return accounts.isLoading ? '—' : accountId;
  }

  const trialBalanceColumns = [
    { key: 'code', header: t('accountantPage.code'), accessor: (a) => a.code, sortable: true, className: 'font-mono font-semibold' },
    { key: 'name', header: t('common.name'), accessor: (a) => a.name, sortable: true },
    { key: 'type', header: t('accountantPage.accountType'), accessor: (a) => t(`accountType.${a.type}`, { defaultValue: a.type }), sortable: true },
    { key: 'debitTotal', header: t('accountantPage.debitTotal'), accessor: (a) => a.debitTotal, sortable: true, render: (a) => formatCurrency(a.debitTotal) },
    { key: 'creditTotal', header: t('accountantPage.creditTotal'), accessor: (a) => a.creditTotal, sortable: true, render: (a) => formatCurrency(a.creditTotal) },
    { key: 'balance', header: t('accountantPage.balance'), accessor: (a) => a.balance, sortable: true, className: 'font-semibold', render: (a) => formatCurrency(a.balance) },
  ];

  const entryColumns = [
    { key: 'description', header: t('accountantPage.description'), accessor: (e) => e.entry.description, sortable: true, className: 'font-semibold' },
    { key: 'sourceType', header: t('accountantPage.sourceType'), accessor: (e) => t(`journalSourceType.${e.entry.sourceType}`, { defaultValue: e.entry.sourceType }), sortable: true },
    { key: 'createdAt', header: t('accountantPage.postedAt'), accessor: (e) => e.entry.createdAt, sortable: true, render: (e) => formatDateTime(e.entry.createdAt) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.accountant.journal')} description={t('accountantPage.journalDescription')} />

      <Card className="mb-8">
        <h2 className="mb-4 text-lg font-bold text-ink">{t('accountantPage.trialBalance')}</h2>
        <DataTable
          columns={trialBalanceColumns}
          rows={trialBalance.data || []}
          rowKey="accountId"
          defaultSortKey="code"
          isLoading={trialBalance.isLoading}
          error={trialBalance.isError ? trialBalance.error : null}
          onRetry={trialBalance.refetch}
          emptyTitle={t('accountantPage.emptyAccountsTitle')}
        />
      </Card>

      <div>
        <h2 className="mb-4 text-lg font-bold text-ink">{t('accountantPage.journalEntries')}</h2>

        <Card className="mb-4 flex flex-wrap items-end gap-3">
          <Field label={t('accountantPage.year')}>
            <select value={yearFilter} onChange={(e) => setYearFilter(e.target.value)} className={`${inputClass} w-28`}>
              <option value="">{t('common.all')}</option>
              {years.map((y) => <option key={y} value={y}>{y}</option>)}
            </select>
          </Field>
          <Field label={t('accountantPage.month')}>
            <select value={monthFilter} onChange={(e) => setMonthFilter(e.target.value)} className={`${inputClass} w-28`}>
              <option value="">{t('common.all')}</option>
              {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
                <option key={m} value={m}>{String(m).padStart(2, '0')}</option>
              ))}
            </select>
          </Field>
          <Field label={t('accountantPage.sourceType')}>
            <select value={sourceTypeFilter} onChange={(e) => setSourceTypeFilter(e.target.value)} className={`${inputClass} w-48`}>
              <option value="">{t('common.all')}</option>
              {sourceTypes.map((s) => (
                <option key={s} value={s}>{t(`journalSourceType.${s}`, { defaultValue: s })}</option>
              ))}
            </select>
          </Field>
        </Card>

        <DataTable
          columns={entryColumns}
          rows={visibleEntries}
          rowKey={(e) => e.entry.id}
          searchAccessors={[(e) => e.entry.description, (e) => e.entry.sourceType]}
          defaultSortKey="createdAt"
          defaultSortDir="desc"
          renderExpanded={(e) => <JournalLines lines={e.lines} accountLabel={accountLabel} />}
          isLoading={entries.isLoading}
          error={entries.isError ? entries.error : null}
          onRetry={entries.refetch}
          emptyTitle={t('accountantPage.emptyJournalTitle')}
          emptyDescription={t('accountantPage.emptyJournalDescription')}
        />
      </div>
    </PageContainer>
  );
}

function JournalLines({ lines, accountLabel }) {
  const { t } = useTranslation();
  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-ink-muted">
          <th className="py-1 pr-4 font-semibold">{t('accountantPage.account')}</th>
          <th className="py-1 pr-4 font-semibold">{t('accountantPage.entryType')}</th>
          <th className="py-1 font-semibold">{t('accountantPage.amount')}</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((line) => (
          <tr key={line.id} className="border-t border-slate-100">
            <td className="py-1.5 pr-4">{accountLabel(line.accountId)}</td>
            <td className="py-1.5 pr-4 capitalize">{t(`entryType.${line.entryType}`, { defaultValue: line.entryType })}</td>
            <td className="py-1.5 font-mono">{formatCurrency(line.amount)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
