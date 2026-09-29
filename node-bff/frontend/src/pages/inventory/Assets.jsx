import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useAssets,
  useCreateAsset,
  useUpdateAsset,
  useAssignAssetRoom,
  useAssetMaintenanceRecords,
  useCreateAssetMaintenanceRecord,
  useRooms,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const STATUSES = ['in_service', 'under_maintenance', 'retired', 'disposed'];

/** Equipment/asset tracking - GET/POST/POST .../update AssetController, a dedicated assign-room action, and a nested append-only maintenance log. Same DataTable+renderExpanded shape as Items.jsx. */
export default function InventoryAssets() {
  const { t } = useTranslation();
  const { data: assets, isLoading, isError, error, refetch } = useAssets(true);
  const rooms = useRooms(true, 'active');
  // Unfiltered, purely for resolving an already-assigned room's own name -
  // an asset can stay assigned to a room that's since been deactivated,
  // and the active-only list above (correctly scoped to the assign picker)
  // would wrongly show "not assigned" for it.
  const allRooms = useRooms(true);
  const createAsset = useCreateAsset();

  const [form, setForm] = useState({ name: '', serialNumber: '', purchaseDate: '', purchasePrice: '', warrantyExpiry: '' });
  const [formError, setFormError] = useState(null);

  const roomById = useMemo(() => Object.fromEntries((allRooms.data || []).map((r) => [r.id, r])), [allRooms.data]);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.name.trim()) {
      setFormError(t('pharmacistPage.errorNameRequired'));
      return;
    }
    try {
      await createAsset.mutateAsync({
        name: form.name.trim(),
        serialNumber: form.serialNumber.trim() || undefined,
        purchaseDate: form.purchaseDate || undefined,
        purchasePrice: form.purchasePrice === '' ? undefined : Number(form.purchasePrice),
        warrantyExpiry: form.warrantyExpiry || undefined,
      });
      setForm({ name: '', serialNumber: '', purchaseDate: '', purchasePrice: '', warrantyExpiry: '' });
    } catch (err) {
      setFormError(err.message || t('inventoryPage.errorCreateAsset'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (a) => a.name, sortable: true, className: 'font-semibold' },
    { key: 'serialNumber', header: t('inventoryPage.serialNumberOptional'), accessor: (a) => a.serialNumber || '', render: (a) => a.serialNumber || '—' },
    { key: 'purchasePrice', header: t('inventoryPage.purchasePriceOptional'), accessor: (a) => a.purchasePrice ?? 0, sortable: true, render: (a) => (a.purchasePrice != null ? formatCurrency(a.purchasePrice) : '—') },
    { key: 'status', header: t('referralsPage.status'), accessor: (a) => a.status, sortable: true, render: (a) => <StatusPill status={a.status} /> },
    {
      key: 'assignedRoom',
      header: t('inventoryPage.assignedRoom'),
      accessor: (a) => (a.assignedRoomId && roomById[a.assignedRoomId]?.name) || '',
      render: (a) => (a.assignedRoomId && roomById[a.assignedRoomId]?.name) || t('inventoryPage.noRoomAssigned'),
    },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.inventory.assets')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('common.name')}>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Autoclave" className={`${inputClass} w-56`} />
        </Field>
        <Field label={t('inventoryPage.serialNumberOptional')}>
          <input value={form.serialNumber} onChange={(e) => setForm({ ...form, serialNumber: e.target.value })} className={`${inputClass} w-40`} />
        </Field>
        <Field label={t('inventoryPage.purchaseDateOptional')}>
          <input type="date" value={form.purchaseDate} onChange={(e) => setForm({ ...form, purchaseDate: e.target.value })} className={inputClass} />
        </Field>
        <Field label={t('inventoryPage.purchasePriceOptional')}>
          <input type="number" min="0" step="0.01" value={form.purchasePrice} onChange={(e) => setForm({ ...form, purchasePrice: e.target.value })} className={`${inputClass} w-28`} />
        </Field>
        <Field label={t('inventoryPage.warrantyExpiryOptional')}>
          <input type="date" value={form.warrantyExpiry} onChange={(e) => setForm({ ...form, warrantyExpiry: e.target.value })} className={inputClass} />
        </Field>
        <Button type="submit" variant="accent" disabled={createAsset.isPending}>
          {createAsset.isPending ? t('common.adding') : t('inventoryPage.addAsset')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={assets || []}
        rowKey="id"
        searchAccessors={[(a) => a.name, (a) => a.serialNumber]}
        defaultSortKey="name"
        renderExpanded={(asset) => <AssetPanel asset={asset} rooms={rooms.data || []} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('inventoryPage.emptyAssetsTitle')}
        emptyDescription={t('inventoryPage.emptyAssetsDescription')}
      />
    </PageContainer>
  );
}

function AssetPanel({ asset, rooms }) {
  const { t } = useTranslation();
  const updateAsset = useUpdateAsset(asset.id);
  const assignRoom = useAssignAssetRoom(asset.id);
  const { data: records, isLoading, isError, error, refetch } = useAssetMaintenanceRecords(asset.id);
  const createRecord = useCreateAssetMaintenanceRecord(asset.id);

  const [rowError, setRowError] = useState(null);
  const [roomSelection, setRoomSelection] = useState(asset.assignedRoomId || '');
  const [recordForm, setRecordForm] = useState({ description: '', notes: '' });
  const [recordError, setRecordError] = useState(null);

  async function handleStatusChange(status) {
    setRowError(null);
    try {
      await updateAsset.mutateAsync({ status });
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleAssignRoom() {
    setRowError(null);
    try {
      await assignRoom.mutateAsync(roomSelection || null);
    } catch (err) {
      setRowError(err.message || t('inventoryPage.errorAssignRoom'));
    }
  }

  async function handleAddRecord(event) {
    event.preventDefault();
    setRecordError(null);
    if (!recordForm.description.trim()) {
      setRecordError(t('inventoryPage.errorDescriptionRequired'));
      return;
    }
    try {
      await createRecord.mutateAsync({ description: recordForm.description.trim(), notes: recordForm.notes.trim() || undefined });
      setRecordForm({ description: '', notes: '' });
    } catch (err) {
      setRecordError(err.message || t('inventoryPage.errorAddMaintenanceRecord'));
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center gap-4">
        <div className="flex items-center gap-2 text-sm">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('referralsPage.status')}</span>
          <select value={asset.status} onChange={(e) => handleStatusChange(e.target.value)} className={`${inputClass} w-44`}>
            {STATUSES.map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
          </select>
        </div>
        <div className="flex items-center gap-2 text-sm">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('inventoryPage.assignedRoom')}</span>
          <select value={roomSelection} onChange={(e) => setRoomSelection(e.target.value)} className={`${inputClass} w-44`}>
            <option value="">{t('inventoryPage.noRoomAssigned')}</option>
            {rooms.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
          </select>
          <Button type="button" variant="secondary" size="sm" onClick={handleAssignRoom} disabled={assignRoom.isPending}>
            {roomSelection ? t('inventoryPage.assignRoom') : t('inventoryPage.clearAssignment')}
          </Button>
        </div>
      </div>
      {rowError && <div className="mt-3"><ErrorBanner message={rowError} /></div>}

      <div className="mt-4 border-t border-slate-100 pt-4">
        <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('inventoryPage.maintenanceRecords')}</p>
        {isLoading && <Skeleton className="h-12 w-full" />}
        {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
        {!isLoading && !isError && (records || []).length === 0 && (
          <p className="mb-3 text-sm text-ink-muted">{t('inventoryPage.noMaintenanceRecords')}</p>
        )}
        {!isLoading && !isError && (records || []).length > 0 && (
          <ul className="mb-3 flex flex-col gap-1 text-sm text-ink">
            {records.map((r) => (
              <li key={r.id}>
                {formatDateTime(r.performedAt)} - {r.description}
                {r.notes && ` - ${r.notes}`}
              </li>
            ))}
          </ul>
        )}
        <form onSubmit={handleAddRecord} className="flex flex-wrap items-end gap-3">
          <Field label={t('inventoryPage.descriptionLabel')}>
            <input value={recordForm.description} onChange={(e) => setRecordForm({ ...recordForm, description: e.target.value })} className={`${inputClass} w-56`} />
          </Field>
          <Field label={t('pharmacistPage.notesOptional')}>
            <input value={recordForm.notes} onChange={(e) => setRecordForm({ ...recordForm, notes: e.target.value })} className={`${inputClass} w-56`} />
          </Field>
          <Button type="submit" variant="accent" disabled={createRecord.isPending}>
            {t('inventoryPage.addMaintenanceRecord')}
          </Button>
        </form>
        {recordError && <div className="mt-3"><ErrorBanner message={recordError} /></div>}
      </div>
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
