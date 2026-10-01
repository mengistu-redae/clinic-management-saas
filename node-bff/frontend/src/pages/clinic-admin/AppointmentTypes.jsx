import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAppointmentTypes, useCreateAppointmentType, useUpdateAppointmentType } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency } from '../../lib/format.js';

const emptyForm = { name: '', durationMinutes: '30', priceAmount: '' };

/** clinic-admin appointment-type management - GET/POST/POST .../update AppointmentTypeController. All statuses shown, same reasoning as Providers.jsx. */
export default function ClinicAdminAppointmentTypes() {
  const { t } = useTranslation();
  const { data: types, isLoading, isError, error, refetch } = useAppointmentTypes(true);
  const createType = useCreateAppointmentType();

  const [form, setForm] = useState(emptyForm);
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const duration = Number(form.durationMinutes);
    const price = Number(form.priceAmount);
    if (!form.name.trim() || !duration || duration <= 0 || form.priceAmount === '' || price < 0) {
      setFormError(t('appointmentTypesPage.errorFields'));
      return;
    }
    try {
      await createType.mutateAsync({ name: form.name.trim(), durationMinutes: duration, priceAmount: price });
      setForm(emptyForm);
    } catch (err) {
      setFormError(err.message || t('appointmentTypesPage.errorCreate'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (type) => type.name, sortable: true, className: 'font-semibold' },
    { key: 'duration', header: t('appointmentTypesPage.duration'), accessor: (type) => type.durationMinutes, sortable: true },
    { key: 'price', header: t('appointmentTypesPage.price'), accessor: (type) => type.priceAmount, sortable: true, render: (type) => formatCurrency(type.priceAmount) },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (type) => type.status,
      sortable: true,
      render: (type) => <StatusPill status={type.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <h1 className="mb-6 text-2xl font-bold text-ink">{t('nav.clinicAdmin.appointmentTypes')}</h1>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="New Patient / 30 min" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('appointmentTypesPage.duration')}>
          <input type="number" min="1" value={form.durationMinutes} onChange={(e) => setForm({ ...form, durationMinutes: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('appointmentTypesPage.price')}>
          <input type="number" min="0" step="0.01" value={form.priceAmount} onChange={(e) => setForm({ ...form, priceAmount: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createType.isPending}>
          {createType.isPending ? t('common.adding') : t('appointmentTypesPage.addType')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={types || []}
        rowKey="id"
        searchAccessors={[(type) => type.name]}
        defaultSortKey="name"
        renderExpanded={(type) => <TypeEditPanel type={type} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('appointmentTypesPage.emptyTitle')}
        emptyDescription={t('appointmentTypesPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function TypeEditPanel({ type }) {
  const { t } = useTranslation();
  const updateType = useUpdateAppointmentType(type.id);
  const [editForm, setEditForm] = useState({
    name: type.name,
    durationMinutes: String(type.durationMinutes),
    priceAmount: String(type.priceAmount),
  });
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateType.mutateAsync({
        name: editForm.name.trim(),
        durationMinutes: Number(editForm.durationMinutes),
        priceAmount: Number(editForm.priceAmount),
      });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateType.mutateAsync({ status: type.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('appointmentTypesPage.errorUpdate'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('common.name')}>
          <input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('appointmentTypesPage.duration')}>
          <input type="number" min="1" value={editForm.durationMinutes} onChange={(e) => setEditForm({ ...editForm, durationMinutes: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('appointmentTypesPage.price')}>
          <input type="number" min="0" step="0.01" value={editForm.priceAmount} onChange={(e) => setEditForm({ ...editForm, priceAmount: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Button type="button" variant="accent" onClick={saveEdit} disabled={updateType.isPending}>
          {t('common.save')}
        </Button>
        <button type="button" onClick={toggleActive} className="text-sm text-ink-muted hover:underline">
          {type.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
        </button>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}
    </div>
  );
}
