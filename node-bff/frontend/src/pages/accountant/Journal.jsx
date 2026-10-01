import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useAccounts, useJournalEntries, useTrialBalance } from '../../api/queries.js';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Card from '../../components/Card.jsx';
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

  const accountById = useMemo(
    () => Object.fromEntries((accounts.data || []).map((a) => [a.id, a])),
    [accounts.data],
  );

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
        <DataTable
          columns={entryColumns}
          rows={entries.data || []}
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
