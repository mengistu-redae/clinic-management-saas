import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAppointmentTypes, useCreateAppointmentType, useUpdateAppointmentType } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

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

  return (
    <div>
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
        <button type="submit" disabled={createType.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createType.isPending ? t('common.adding') : t('appointmentTypesPage.addType')}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-24 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && types?.length === 0 && (
        <EmptyState title={t('appointmentTypesPage.emptyTitle')} description={t('appointmentTypesPage.emptyDescription')} />
      )}

      {!isLoading && !isError && types?.length > 0 && (
        <div className="flex flex-col gap-2">
          {types.map((type) => (
            <TypeRow key={type.id} type={type} />
          ))}
        </div>
      )}
    </div>
  );
}

function TypeRow({ type }) {
  const { t } = useTranslation();
  const updateType = useUpdateAppointmentType(type.id);

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [rowError, setRowError] = useState(null);

  function startEdit() {
    setRowError(null);
    setEditForm({ name: type.name, durationMinutes: String(type.durationMinutes), priceAmount: String(type.priceAmount) });
    setEditing(true);
  }

  async function saveEdit() {
    setRowError(null);
    try {
      await updateType.mutateAsync({
        name: editForm.name.trim(),
        durationMinutes: Number(editForm.durationMinutes),
        priceAmount: Number(editForm.priceAmount),
      });
      setEditing(false);
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
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      {editing ? (
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
          <button type="button" onClick={saveEdit} disabled={updateType.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {t('common.save')}
          </button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <StatusPill status={type.status} />
            <div>
              <p className="text-sm font-semibold text-ink">{type.name}</p>
              <p className="text-xs text-ink-muted">{type.durationMinutes} min · {formatCurrency(type.priceAmount)}</p>
            </div>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <button type="button" onClick={startEdit} className="text-brand hover:underline">{t('common.edit')}</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {type.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
            </button>
          </div>
        </div>
      )}
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
