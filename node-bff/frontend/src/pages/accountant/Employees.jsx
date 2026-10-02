import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useEmployees, useCreateEmployee, useUpdateEmployee } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Payroll roster - GET/POST/POST .../update EmployeeController. An
 * employee is added by the email of a staff account that has already
 * logged in at least once (same ProviderController.linkLogin precedent -
 * this app has no general staff-directory endpoint to pick from
 * otherwise); the account's own name/email are snapshotted server-side at
 * creation, not editable here.
 */
export default function AccountantEmployees() {
  const { t } = useTranslation();
  const { data: employees, isLoading, isError, error, refetch } = useEmployees(true);
  const createEmployee = useCreateEmployee();

  const [form, setForm] = useState({ email: '', salaryAmount: '' });
  const [formError, setFormError] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');

  const visibleEmployees = (employees || []).filter((e) => !statusFilter || e.status === statusFilter);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const salary = Number(form.salaryAmount);
    if (!form.email.trim() || !form.salaryAmount || salary <= 0) {
      setFormError(t('accountantPage.errorEmployeeFieldsRequired'));
      return;
    }
    try {
      await createEmployee.mutateAsync({ email: form.email.trim(), salaryAmount: salary });
      setForm({ email: '', salaryAmount: '' });
    } catch (err) {
      setFormError(err.message || t('accountantPage.errorCreateEmployee'));
    }
  }

  const columns = [
    { key: 'fullName', header: t('common.name'), accessor: (e) => e.fullName || e.email, sortable: true, className: 'font-semibold' },
    { key: 'email', header: t('accountantPage.email'), accessor: (e) => e.email, sortable: true },
    { key: 'salaryAmount', header: t('accountantPage.salary'), accessor: (e) => e.salaryAmount, sortable: true, render: (e) => formatCurrency(e.salaryAmount) },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (e) => e.status,
      sortable: true,
      render: (e) => <StatusPill status={e.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.accountant.employees')} description={t('accountantPage.employeesDescription')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('accountantPage.email')}>
          <input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} placeholder="demo-front-desk@example.test" className={`${inputClass} w-64`} />
        </Field>
        <Field label={t('accountantPage.salary')}>
          <input type="number" min="0" step="0.01" value={form.salaryAmount} onChange={(e) => setForm({ ...form, salaryAmount: e.target.value })} className={`${inputClass} w-32`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createEmployee.isPending}>
          {createEmployee.isPending ? t('common.adding') : t('accountantPage.addEmployee')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <div className="mb-4 flex items-end gap-3">
        <Field label={t('referralsPage.status')}>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className={`${inputClass} w-40`}>
            <option value="">{t('common.all')}</option>
            <option value="active">{t('status.active')}</option>
            <option value="inactive">{t('status.inactive')}</option>
          </select>
        </Field>
      </div>

      <DataTable
        columns={columns}
        rows={visibleEmployees}
        rowKey="id"
        searchAccessors={[(e) => e.fullName, (e) => e.email]}
        defaultSortKey="fullName"
        renderExpanded={(employee) => <EmployeeEditPanel employee={employee} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('accountantPage.emptyEmployeesTitle')}
        emptyDescription={t('accountantPage.emptyEmployeesDescription')}
      />
    </PageContainer>
  );
}

function EmployeeEditPanel({ employee }) {
  const { t } = useTranslation();
  const updateEmployee = useUpdateEmployee(employee.id);
  const [salaryAmount, setSalaryAmount] = useState(String(employee.salaryAmount));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    const salary = Number(salaryAmount);
    if (!salaryAmount || salary <= 0) {
      setRowError(t('accountantPage.errorEmployeeFieldsRequired'));
      return;
    }
    try {
      await updateEmployee.mutateAsync({ salaryAmount: salary });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateEmployee.mutateAsync({ status: employee.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('accountantPage.email')}>
          <span className={`${inputClass} inline-block w-64 bg-slate-50 text-ink-muted`}>{employee.email}</span>
        </Field>
        <Field label={t('accountantPage.salary')}>
          <input type="number" min="0" step="0.01" value={salaryAmount} onChange={(e) => setSalaryAmount(e.target.value)} className={`${inputClass} w-32`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateEmployee.isPending}>
          {t('common.save')}
        </Button>
        <button type="button" onClick={toggleActive} className="text-sm text-ink-muted hover:underline">
          {employee.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
        </button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
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
