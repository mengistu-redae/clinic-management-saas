import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useMedications,
  useCreateMedication,
  useUpdateMedication,
  useUpdateControlledSubstanceSchedule,
  useStockBatches,
  useReceiveStockBatch,
  useWriteOffStockBatch,
  useReorderAlerts,
} from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency } from '../../lib/format.js';

const FORMS = ['tablet', 'capsule', 'syrup', 'injection', 'other'];
const WRITE_OFF_STATUSES = ['expired', 'recalled'];
const SCHEDULES = ['schedule_i', 'schedule_ii', 'schedule_iii', 'schedule_iv', 'schedule_v'];

/**
 * The pharmacy catalog - GET/POST/POST .../update MedicationController,
 * plus a nested per-medication stock-batches panel (receive/write-off),
 * same DataTable+renderExpanded shape clinic-admin/Providers.jsx already
 * established for its own nested hours/login/signature panels this
 * session. All statuses shown - a pharmacist needs to see and reactivate
 * deactivated medications too.
 */
export default function Medications() {
  const { t } = useTranslation();
  const { data: medications, isLoading, isError, error, refetch } = useMedications(true);
  const { data: reorderAlerts } = useReorderAlerts(true);
  const createMedication = useCreateMedication();

  const [form, setForm] = useState({ name: '', form: 'tablet', unitOfMeasure: '', unitPrice: '', reorderThreshold: '' });
  const [formError, setFormError] = useState(null);
  const [triageFilter, setTriageFilter] = useState('');

  // Reuses the shared, already-computed reorder-alerts endpoint (same one
  // InventoryItemController.reorderAlerts/the inventory dashboard's own
  // low-stock badge feed from) rather than fetching every medication's own
  // stock batches up front just to compute a list-level flag - an N+1 this
  // app has deliberately avoided elsewhere. No bulk "expiring soon" source
  // is reachable from the pharmacist role today (the one that exists,
  // GET /api/inventory/analytics, is clinic_admin/front_desk-only) - an
  // "Expiring soon" filter option was in scope but isn't included here for
  // that reason, flagged rather than worked around with a per-row fetch.
  const lowStockMedicationIds = new Set(
    (reorderAlerts || []).filter((a) => a.ownerType === 'medication').map((a) => a.ownerId),
  );

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.name.trim()) {
      setFormError(t('pharmacistPage.errorNameRequired'));
      return;
    }
    try {
      await createMedication.mutateAsync({
        name: form.name.trim(),
        form: form.form,
        unitOfMeasure: form.unitOfMeasure.trim() || undefined,
        unitPrice: form.unitPrice === '' ? undefined : Number(form.unitPrice),
        reorderThreshold: form.reorderThreshold === '' ? undefined : Number(form.reorderThreshold),
      });
      setForm({ name: '', form: 'tablet', unitOfMeasure: '', unitPrice: '', reorderThreshold: '' });
    } catch (err) {
      setFormError(err.message || t('pharmacistPage.errorCreate'));
    }
  }

  const visibleMedications = (medications || []).filter((m) => {
    if (triageFilter === 'active') return m.status === 'active';
    if (triageFilter === 'lowStock') return lowStockMedicationIds.has(m.id);
    if (triageFilter === 'controlled') return m.controlledSubstanceSchedule != null;
    return true;
  });

  const columns = [
    {
      key: 'name',
      header: t('common.name'),
      accessor: (m) => m.name,
      sortable: true,
      className: 'font-semibold',
      render: (m) => (
        <span>
          {m.name}
          {m.controlledSubstanceSchedule && (
            <span className="ml-2 inline-flex items-center rounded-full bg-warning-light px-2 py-0.5 text-xs font-semibold text-warning">
              {t(`controlledSubstancesPage.scheduleLabel_${m.controlledSubstanceSchedule}`, { defaultValue: m.controlledSubstanceSchedule })}
            </span>
          )}
        </span>
      ),
    },
    { key: 'form', header: t('pharmacistPage.form'), accessor: (m) => t(`medicationForm.${m.form}`, { defaultValue: m.form }), sortable: true },
    { key: 'unitPrice', header: t('appointmentTypesPage.price'), accessor: (m) => m.unitPrice, sortable: true, render: (m) => formatCurrency(m.unitPrice) },
    { key: 'reorderThreshold', header: t('pharmacistPage.reorderThreshold'), accessor: (m) => m.reorderThreshold, sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (m) => m.status,
      sortable: true,
      render: (m) => <StatusPill status={m.status} />,
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader
        title={t('nav.pharmacist.medications')}
        actions={
          <select
            value={triageFilter}
            onChange={(e) => setTriageFilter(e.target.value)}
            aria-label={t('common.filterByStatus')}
            className={`${inputClass} w-44`}
          >
            <option value="">{t('common.all')}</option>
            <option value="active">{t('common.activeOnly')}</option>
            <option value="lowStock">{t('pharmacistPage.lowStock')}</option>
            <option value="controlled">{t('nav.pharmacist.controlledSubstances')}</option>
          </select>
        }
      />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Amoxicillin 500mg" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('pharmacistPage.form')}>
          <select value={form.form} onChange={(e) => setForm({ ...form, form: e.target.value })} className={`${inputClass} w-36`}>
            {FORMS.map((f) => <option key={f} value={f}>{t(`medicationForm.${f}`)}</option>)}
          </select>
        </Field>
        <Field label={t('pharmacistPage.unitOfMeasureOptional')}>
          <input value={form.unitOfMeasure} onChange={(e) => setForm({ ...form, unitOfMeasure: e.target.value })} className={`${inputClass} w-32`} />
        </Field>
        <Field label={t('pharmacistPage.unitPriceOptional')}>
          <input type="number" min="0" step="0.01" value={form.unitPrice} onChange={(e) => setForm({ ...form, unitPrice: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('pharmacistPage.reorderThresholdOptional')}>
          <input type="number" min="0" value={form.reorderThreshold} onChange={(e) => setForm({ ...form, reorderThreshold: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Button type="submit" variant="accent" disabled={createMedication.isPending}>
          {createMedication.isPending ? t('common.adding') : t('pharmacistPage.addMedication')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={visibleMedications}
        rowKey="id"
        searchAccessors={[(m) => m.name, (m) => t(`medicationForm.${m.form}`, { defaultValue: m.form })]}
        defaultSortKey="name"
        renderExpanded={(medication) => <StockBatchesPanel medication={medication} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('pharmacistPage.emptyMedicationsTitle')}
        emptyDescription={t('pharmacistPage.emptyMedicationsDescription')}
      />
    </PageContainer>
  );
}

function StockBatchesPanel({ medication }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const updateMedication = useUpdateMedication(medication.id);
  const updateSchedule = useUpdateControlledSubstanceSchedule(medication.id);
  const { data: batches, isLoading, isError, error, refetch } = useStockBatches(medication.id);
  const receiveBatch = useReceiveStockBatch(medication.id);
  const writeOffBatch = useWriteOffStockBatch(medication.id);

  const [editForm, setEditForm] = useState(null);
  const [editing, setEditing] = useState(false);
  const [rowError, setRowError] = useState(null);

  const [batchForm, setBatchForm] = useState({ batchNumber: '', quantityReceived: '', expiryDate: '' });
  const [batchError, setBatchError] = useState(null);
  const [writeOffTarget, setWriteOffTarget] = useState(null); // { batchId, status } | null
  const [writeOffReason, setWriteOffReason] = useState('');

  function startEdit() {
    setRowError(null);
    setEditForm({
      name: medication.name, form: medication.form, unitOfMeasure: medication.unitOfMeasure || '',
      unitPrice: String(medication.unitPrice), reorderThreshold: String(medication.reorderThreshold),
    });
    setEditing(true);
  }

  const activeBatches = (batches || []).filter((b) => b.status === 'active');
  const onHandTotal = activeBatches.reduce((sum, b) => sum + b.quantityOnHand, 0);
  const isLowStock = onHandTotal <= medication.reorderThreshold;
  const today = new Date().toISOString().slice(0, 10);
  const expiringSoon = activeBatches.filter((b) => b.expiryDate && b.expiryDate <= today);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateMedication.mutateAsync({
        name: editForm.name.trim(),
        form: editForm.form,
        unitOfMeasure: editForm.unitOfMeasure.trim() || null,
        unitPrice: Number(editForm.unitPrice),
        reorderThreshold: Number(editForm.reorderThreshold),
      });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function toggleActive() {
    setRowError(null);
    try {
      await updateMedication.mutateAsync({ status: medication.status === 'active' ? 'inactive' : 'active' });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleScheduleChange(event) {
    setRowError(null);
    try {
      await updateSchedule.mutateAsync({ schedule: event.target.value || null });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleReceive(event) {
    event.preventDefault();
    setBatchError(null);
    const quantity = Number(batchForm.quantityReceived);
    if (!quantity || quantity <= 0) {
      setBatchError(t('pharmacistPage.errorQuantityRequired'));
      return;
    }
    try {
      await receiveBatch.mutateAsync({
        batchNumber: batchForm.batchNumber.trim() || undefined,
        quantityReceived: quantity,
        expiryDate: batchForm.expiryDate || undefined,
      });
      setBatchForm({ batchNumber: '', quantityReceived: '', expiryDate: '' });
    } catch (err) {
      setBatchError(err.message || t('pharmacistPage.errorReceive'));
    }
  }

  async function confirmWriteOff(event) {
    event.preventDefault();
    setBatchError(null);
    if (!writeOffReason.trim()) {
      setBatchError(t('pharmacistPage.errorReasonRequired'));
      return;
    }
    try {
      await writeOffBatch.mutateAsync({ batchId: writeOffTarget.batchId, status: writeOffTarget.status, reason: writeOffReason.trim() });
      setWriteOffTarget(null);
      setWriteOffReason('');
    } catch (err) {
      setBatchError(err.message || t('pharmacistPage.errorWriteOff'));
    }
  }

  return (
    <div>
      {editing ? (
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t('common.name')}>
            <input value={editForm.name} onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} className={`${inputClass} w-56`} />
          </Field>
          <Field label={t('pharmacistPage.form')}>
            <select value={editForm.form} onChange={(e) => setEditForm({ ...editForm, form: e.target.value })} className={`${inputClass} w-36`}>
              {FORMS.map((f) => <option key={f} value={f}>{t(`medicationForm.${f}`)}</option>)}
            </select>
          </Field>
          <Field label={t('pharmacistPage.unitPriceOptional')}>
            <input type="number" min="0" step="0.01" value={editForm.unitPrice} onChange={(e) => setEditForm({ ...editForm, unitPrice: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Field label={t('pharmacistPage.reorderThresholdOptional')}>
            <input type="number" min="0" value={editForm.reorderThreshold} onChange={(e) => setEditForm({ ...editForm, reorderThreshold: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Button type="button" variant="accent" onClick={saveEdit} disabled={updateMedication.isPending}>
            {t('common.save')}
          </Button>
          <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            {isLowStock && (
              <span className="inline-flex items-center rounded-full bg-danger-light px-2.5 py-0.5 text-xs font-semibold text-danger">
                {t('pharmacistPage.lowStock')}
              </span>
            )}
            <span className="text-sm text-ink">{t('pharmacistPage.onHandTotal', { count: onHandTotal })}</span>
            {expiringSoon.length > 0 && (
              <span className="text-xs font-semibold text-danger">{t('pharmacistPage.expiringSoonCount', { count: expiringSoon.length })}</span>
            )}
          </div>
          <div className="flex items-center gap-3 text-sm">
            {hasRole('clinic_admin') && (
              <label className="flex items-center gap-1.5 border-r border-slate-200 pr-3 text-xs text-ink-muted">
                {t('controlledSubstancesPage.schedule')}
                <select
                  value={medication.controlledSubstanceSchedule || ''}
                  onChange={handleScheduleChange}
                  disabled={updateSchedule.isPending}
                  className={`${inputClass} w-32 py-1`}
                >
                  <option value="">{t('controlledSubstancesPage.scheduleUnset')}</option>
                  {SCHEDULES.map((s) => <option key={s} value={s}>{t(`controlledSubstancesPage.scheduleLabel_${s}`)}</option>)}
                </select>
              </label>
            )}
            <div className="flex items-center gap-3">
              <button type="button" onClick={startEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
              <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
                {medication.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
              </button>
            </div>
          </div>
        </div>
      )}
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}

      <div className="mt-4 border-t border-slate-100 pt-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('pharmacistPage.stockBatches')}</p>
        {isLoading && <Skeleton className="h-12 w-full" />}
        {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
        {!isLoading && !isError && (batches || []).length === 0 && (
          <p className="mb-3 text-sm text-ink-muted">{t('pharmacistPage.noBatches')}</p>
        )}
        {!isLoading && !isError && (batches || []).length > 0 && (
          <ul className="mb-3 flex flex-col gap-1.5">
            {batches.map((b) => (
              <li key={b.id} className="flex flex-col gap-2">
                <div className="flex items-center justify-between text-sm text-ink">
                  <span>
                    {b.batchNumber || t('pharmacistPage.unlabeledBatch')} - {b.quantityOnHand}/{b.quantityReceived} {t('pharmacistPage.onHand')}
                    {b.expiryDate && ` - ${b.expiryDate}`} · <StatusPill status={b.status} />
                  </span>
                  {b.status === 'active' && (
                    <span className="flex items-center gap-2 text-xs">
                      {WRITE_OFF_STATUSES.map((status) => (
                        <button
                          key={status}
                          type="button"
                          onClick={() => { setWriteOffTarget({ batchId: b.id, status }); setWriteOffReason(''); setBatchError(null); }}
                          className="text-danger hover:underline"
                        >
                          {t(`pharmacistPage.mark_${status}`)}
                        </button>
                      ))}
                    </span>
                  )}
                </div>
                {writeOffTarget?.batchId === b.id && (
                  <form onSubmit={confirmWriteOff} className="flex flex-wrap items-end gap-3 rounded-lg bg-slate-50 p-3">
                    <Field label={t('pharmacistPage.writeOffReasonLabel')}>
                      <input autoFocus value={writeOffReason} onChange={(e) => setWriteOffReason(e.target.value)} className={`${inputClass} w-64`} />
                    </Field>
                    <Button type="submit" variant="danger" size="sm" disabled={writeOffBatch.isPending}>
                      {t(`pharmacistPage.mark_${writeOffTarget.status}`)}
                    </Button>
                    <button type="button" onClick={() => setWriteOffTarget(null)} className="text-sm text-ink-muted hover:underline">
                      {t('common.cancel')}
                    </button>
                  </form>
                )}
              </li>
            ))}
          </ul>
        )}

        <form onSubmit={handleReceive} className="flex flex-wrap items-end gap-3">
          <Field label={t('pharmacistPage.batchNumberOptional')}>
            <input value={batchForm.batchNumber} onChange={(e) => setBatchForm({ ...batchForm, batchNumber: e.target.value })} className={`${inputClass} w-36`} />
          </Field>
          <Field label={t('pharmacistPage.quantityReceived')}>
            <input type="number" min="1" value={batchForm.quantityReceived} onChange={(e) => setBatchForm({ ...batchForm, quantityReceived: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Field label={t('pharmacistPage.expiryDateOptional')}>
            <input type="date" value={batchForm.expiryDate} onChange={(e) => setBatchForm({ ...batchForm, expiryDate: e.target.value })} className={inputClass} />
          </Field>
          <Button type="submit" variant="accent" disabled={receiveBatch.isPending}>
            {t('pharmacistPage.receiveStock')}
          </Button>
        </form>
        {batchError && <div className="mt-3"><ErrorBanner message={batchError} /></div>}
      </div>
    </div>
  );
}
