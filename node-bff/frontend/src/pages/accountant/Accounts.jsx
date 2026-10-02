import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAccounts, useCreateAccount, useUpdateAccount } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';

const ACCOUNT_TYPES = ['asset', 'liability', 'equity', 'revenue', 'expense'];

/**
 * The chart of accounts - GET/POST/POST .../update AccountController. All
 * statuses shown (an accountant needs to see and reactivate deactivated
 * accounts too). `code` is fixed at creation, not editable - JournalService
 * resolves the seeded starter accounts (1000/4000/4900/5000) by code, same
 * reasoning LabRate's own testCode stays read-only after creation. The
 * three-then-four starter accounts (Cash/Service Revenue/Refunds &
 * Allowances/Salary Expense) are lazily seeded server-side the first time
 * this list loads - nothing this page needs to trigger itself.
 */
export default function AccountantAccounts() {
  const { t } = useTranslation();
  const { data: accounts, isLoading, isError, error, refetch } = useAccounts(true);
  const createAccount = useCreateAccount();

  const [form, setForm] = useState({ code: '', name: '', type: 'asset' });
  const [formError, setFormError] = useState(null);
  const [typeFilter, setTypeFilter] = useState('');

  const visibleAccounts = (accounts || []).filter((a) => !typeFilter || a.type === typeFilter);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.code.trim() || !form.name.trim()) {
      setFormError(t('accountantPage.errorAccountFieldsRequired'));
      return;
    }
    try {
      await createAccount.mutateAsync({ code: form.code.trim(), name: form.name.trim(), type: form.type });
      setForm({ code: '', name: '', type: 'asset' });
    } catch (err) {
      setFormError(err.message || t('accountantPage.errorCreateAccount'));
    }
  }

  const columns = [
    { key: 'code', header: t('accountantPage.code'), accessor: (a) => a.code, sortable: true, className: 'font-mono font-semibold' },
    { key: 'name', header: t('common.name'), accessor: (a) => a.name, sortable: true },
    { key: 'type', header: t('accountantPage.accountType'), accessor: (a) => t(`accountType.${a.type}`, { defaultValue: a.type }), sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (a) => a.status,
      sortable: true,
      render: (a) => <StatusPill status={a.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('nav.accountant.accounts')}
        description={t('accountantPage.accountsDescription')}
        actions={
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            aria-label={t('accountantPage.accountType')}
            className={`${inputClass} w-40`}
          >
            <option value="">{t('common.all')}</option>
            {ACCOUNT_TYPES.map((type) => <option key={type} value={type}>{t(`accountType.${type}`)}</option>)}
          </select>
        }
      />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('accountantPage.code')}>
          <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} placeholder="2000" className={`${inputClass} w-28 font-mono`} />
        </Field>
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={t('accountantPage.accountNamePlaceholder')} className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('accountantPage.accountType')}>
          <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className={`${inputClass} w-40`}>
            {ACCOUNT_TYPES.map((type) => <option key={type} value={type}>{t(`accountType.${type}`)}</option>)}
          </select>
        </Field>
        <Button type="submit" variant="accent" disabled={createAccount.isPending}>
          {createAccount.isPending ? t('common.adding') : t('accountantPage.addAccount')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={visibleAccounts}
        rowKey="id"
        searchAccessors={[(a) => a.name, (a) => a.code]}
        defaultSortKey="code"
        renderExpanded={(account) => <AccountEditPanel account={account} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('accountantPage.emptyAccountsTitle')}
        emptyDescription={t('accountantPage.emptyAccountsDescription')}
      />
    </PageContainer>
  );
}

function AccountEditPanel({ account }) {
  const { t } = useTranslation();
  const updateAccount = useUpdateAccount(account.id);
  const [name, setName] = useState(account.name);
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateAccount.mutateAsync({ name: name.trim() });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateAccount.mutateAsync({ status: account.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('accountantPage.code')}>
          <span className={`${inputClass} inline-block w-28 bg-slate-50 font-mono text-ink-muted`}>{account.code}</span>
        </Field>
        <Field label={t('common.name')}>
          <input value={name} onChange={(e) => setName(e.target.value)} className={`${inputClass} w-56`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateAccount.isPending}>
          {t('common.save')}
        </Button>
        <button type="button" onClick={toggleActive} className="text-sm text-ink-muted hover:underline">
          {account.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
        </button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
