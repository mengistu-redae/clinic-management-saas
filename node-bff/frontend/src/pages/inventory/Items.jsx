import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useInventoryItems,
  useCreateInventoryItem,
  useUpdateInventoryItem,
  useInventoryStockBatches,
  useReceiveInventoryStockBatch,
  useWriteOffInventoryStockBatch,
  useStockAdjustments,
  useCreateStockAdjustment,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatCurrency } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const CATEGORIES = ['clinical_supply', 'ppe', 'office_supply'];
const WRITE_OFF_STATUSES = ['expired', 'recalled'];
const ADJUSTMENT_REASONS = ['used', 'wasted', 'expired', 'correction', 'other'];

/**
 * General inventory catalog - GET/POST/POST .../update InventoryItemController,
 * plus a nested per-item stock-batches panel (receive/write-off/adjust) -
 * same DataTable+renderExpanded shape pharmacist/Medications.jsx already
 * established for the pharmacy catalog. clinic_admin+front_desk, a
 * co-equal two-role gate (not a primary-role-plus-override the way
 * pharmacist/accountant work - both roles already get full, symmetric
 * backend access).
 */
export default function InventoryItems() {
  const { t } = useTranslation();
  const { data: items, isLoading, isError, error, refetch } = useInventoryItems(true);
  const createItem = useCreateInventoryItem();

  const [form, setForm] = useState({ name: '', category: 'clinical_supply', unitOfMeasure: '', unitPrice: '', reorderThreshold: '' });
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.name.trim()) {
      setFormError(t('pharmacistPage.errorNameRequired'));
      return;
    }
    try {
      await createItem.mutateAsync({
        name: form.name.trim(),
        category: form.category,
        unitOfMeasure: form.unitOfMeasure.trim() || undefined,
        unitPrice: form.unitPrice === '' ? undefined : Number(form.unitPrice),
        reorderThreshold: form.reorderThreshold === '' ? undefined : Number(form.reorderThreshold),
      });
      setForm({ name: '', category: 'clinical_supply', unitOfMeasure: '', unitPrice: '', reorderThreshold: '' });
    } catch (err) {
      setFormError(err.message || t('inventoryPage.errorCreateItem'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (i) => i.name, sortable: true, className: 'font-semibold' },
    { key: 'category', header: t('inventoryPage.category'), accessor: (i) => t(`inventoryCategory.${i.category}`, { defaultValue: i.category }), sortable: true },
    { key: 'unitPrice', header: t('appointmentTypesPage.price'), accessor: (i) => i.unitPrice, sortable: true, render: (i) => formatCurrency(i.unitPrice) },
    { key: 'reorderThreshold', header: t('pharmacistPage.reorderThreshold'), accessor: (i) => i.reorderThreshold, sortable: true },
    { key: 'status', header: t('referralsPage.status'), accessor: (i) => i.status, sortable: true, render: (i) => <StatusPill status={i.status} /> },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.inventory.items')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Nitrile Gloves (Box)" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('inventoryPage.category')}>
          <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} className={`${inputClass} w-40`}>
            {CATEGORIES.map((c) => <option key={c} value={c}>{t(`inventoryCategory.${c}`)}</option>)}
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
        <Button type="submit" variant="accent" disabled={createItem.isPending}>
          {createItem.isPending ? t('common.adding') : t('inventoryPage.addItem')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={items || []}
        rowKey="id"
        searchAccessors={[(i) => i.name]}
        defaultSortKey="name"
        renderExpanded={(item) => <StockBatchesPanel item={item} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('inventoryPage.emptyItemsTitle')}
        emptyDescription={t('inventoryPage.emptyItemsDescription')}
      />
    </PageContainer>
  );
}

function StockBatchesPanel({ item }) {
  const { t } = useTranslation();
  const updateItem = useUpdateInventoryItem(item.id);
  const { data: batches, isLoading, isError, error, refetch } = useInventoryStockBatches(item.id);
  const receiveBatch = useReceiveInventoryStockBatch(item.id);
  const writeOffBatch = useWriteOffInventoryStockBatch(item.id);

  const [editForm, setEditForm] = useState(null);
  const [editing, setEditing] = useState(false);
  const [rowError, setRowError] = useState(null);

  const [batchForm, setBatchForm] = useState({ batchNumber: '', quantityReceived: '', expiryDate: '' });
  const [batchError, setBatchError] = useState(null);
  const [writeOffTarget, setWriteOffTarget] = useState(null); // { batchId, status } | null
  const [writeOffReason, setWriteOffReason] = useState('');
  const [adjustmentsOpenFor, setAdjustmentsOpenFor] = useState(null); // batchId | null

  function startEdit() {
    setRowError(null);
    setEditForm({
      name: item.name, category: item.category, unitOfMeasure: item.unitOfMeasure || '',
      unitPrice: String(item.unitPrice), reorderThreshold: String(item.reorderThreshold),
    });
    setEditing(true);
  }

  const activeBatches = (batches || []).filter((b) => b.status === 'active');
  const onHandTotal = activeBatches.reduce((sum, b) => sum + b.quantityOnHand, 0);
  const isLowStock = onHandTotal <= item.reorderThreshold;
  const today = new Date().toISOString().slice(0, 10);
  const expiringSoon = activeBatches.filter((b) => b.expiryDate && b.expiryDate <= today);

  async function saveEdit() {
    setRowError(null);
    try {
      await updateItem.mutateAsync({
        name: editForm.name.trim(),
        category: editForm.category,
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
      await updateItem.mutateAsync({ status: item.status === 'active' ? 'inactive' : 'active' });
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
          <Field label={t('inventoryPage.category')}>
            <select value={editForm.category} onChange={(e) => setEditForm({ ...editForm, category: e.target.value })} className={`${inputClass} w-40`}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{t(`inventoryCategory.${c}`)}</option>)}
            </select>
          </Field>
          <Field label={t('pharmacistPage.unitPriceOptional')}>
            <input type="number" min="0" step="0.01" value={editForm.unitPrice} onChange={(e) => setEditForm({ ...editForm, unitPrice: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Field label={t('pharmacistPage.reorderThresholdOptional')}>
            <input type="number" min="0" value={editForm.reorderThreshold} onChange={(e) => setEditForm({ ...editForm, reorderThreshold: e.target.value })} className={`${inputClass} w-28`} />
          </Field>
          <Button type="button" variant="accent" onClick={saveEdit} disabled={updateItem.isPending}>
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
            <button type="button" onClick={startEdit} className="text-brand-text hover:underline">{t('common.edit')}</button>
            <button type="button" onClick={toggleActive} className="text-ink-muted hover:underline">
              {item.status === 'active' ? t('common.deactivate') : t('common.reactivate')}
            </button>
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
                  <span className="flex items-center gap-2 text-xs">
                    <button
                      type="button"
                      onClick={() => setAdjustmentsOpenFor(adjustmentsOpenFor === b.id ? null : b.id)}
                      className="text-brand-text hover:underline"
                    >
                      {t('inventoryPage.adjustmentsToggle')}
                    </button>
                    {b.status === 'active' && WRITE_OFF_STATUSES.map((status) => (
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
                {adjustmentsOpenFor === b.id && <AdjustmentsPanel batchId={b.id} />}
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

function AdjustmentsPanel({ batchId }) {
  const { t } = useTranslation();
  const { data: adjustments, isLoading, isError, error, refetch } = useStockAdjustments(batchId);
  const createAdjustment = useCreateStockAdjustment(batchId);

  const [form, setForm] = useState({ quantityDelta: '', reason: 'used', notes: '' });
  const [formError, setFormError] = useState(null);

  async function handleSubmit(event) {
    event.preventDefault();
    setFormError(null);
    const delta = Number(form.quantityDelta);
    if (!delta) {
      setFormError(t('inventoryPage.errorQuantityDeltaRequired'));
      return;
    }
    try {
      await createAdjustment.mutateAsync({ quantityDelta: delta, reason: form.reason, notes: form.notes.trim() || undefined });
      setForm({ quantityDelta: '', reason: 'used', notes: '' });
    } catch (err) {
      setFormError(err.message || t('inventoryPage.errorAdjust'));
    }
  }

  return (
    <div className="rounded-lg bg-slate-50 p-3">
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('inventoryPage.adjustments')}</p>
      {isLoading && <Skeleton className="h-8 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && (adjustments || []).length === 0 && (
        <p className="mb-2 text-sm text-ink-muted">{t('inventoryPage.noAdjustments')}</p>
      )}
      {!isLoading && !isError && (adjustments || []).length > 0 && (
        <ul className="mb-2 flex flex-col gap-1 text-sm text-ink">
          {adjustments.map((a) => (
            <li key={a.id}>
              {a.quantityDelta > 0 ? `+${a.quantityDelta}` : a.quantityDelta} - {t(`inventoryPage.reason${a.reason.charAt(0).toUpperCase()}${a.reason.slice(1)}`, { defaultValue: a.reason })}
              {a.notes && ` - ${a.notes}`}
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
        <Field label={t('inventoryPage.quantityDelta')}>
          <input type="number" value={form.quantityDelta} onChange={(e) => setForm({ ...form, quantityDelta: e.target.value })} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('inventoryPage.reason')}>
          <select value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} className={`${inputClass} w-36`}>
            {ADJUSTMENT_REASONS.map((r) => <option key={r} value={r}>{t(`inventoryPage.reason${r.charAt(0).toUpperCase()}${r.slice(1)}`)}</option>)}
          </select>
        </Field>
        <Field label={t('pharmacistPage.notesOptional')}>
          <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className={`${inputClass} w-48`} />
        </Field>
        <Button type="submit" variant="secondary" size="sm" disabled={createAdjustment.isPending}>
          {t('inventoryPage.addAdjustment')}
        </Button>
      </form>
      {formError && <div className="mt-2"><ErrorBanner message={formError} /></div>}
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
