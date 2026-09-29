import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useSuppliers,
  usePurchaseOrders,
  useCreatePurchaseOrder,
  useReceivePurchaseOrder,
  useCancelPurchaseOrder,
  useMedications,
  useInventoryItems,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

function emptyLine() {
  return { lineType: 'medication', ownerId: '', quantityOrdered: '', unitCost: '' };
}

/**
 * Purchase orders mix medication and inventory-item lines (each order's
 * lines are created in one nested request, per PurchaseOrderController's
 * own shape - not per-line calls). renderExpanded shows the response's
 * already-embedded `lines` (PurchaseOrderWithLines), same "no second
 * fetch needed" pattern accountant/Journal.jsx's own journal-entry lines
 * already established.
 */
export default function InventoryPurchaseOrders() {
  const { t } = useTranslation();
  const suppliers = useSuppliers(true, 'active');
  const medications = useMedications(true, 'active');
  const items = useInventoryItems(true, 'active');
  // Separate, unfiltered lists purely for resolving an existing order's own
  // supplier/line names - a historical order can reference a supplier or
  // catalog row that's since been deactivated, and the active-only lists
  // above (correctly scoped to the create-form's own pickers) would wrongly
  // show a raw id for it.
  const allSuppliers = useSuppliers(true);
  const allMedications = useMedications(true);
  const allItems = useInventoryItems(true);
  const { data: orders, isLoading, isError, error, refetch } = usePurchaseOrders(true);
  const createOrder = useCreatePurchaseOrder();

  const [supplierId, setSupplierId] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState([emptyLine()]);
  const [formError, setFormError] = useState(null);

  const supplierById = useMemo(() => Object.fromEntries((allSuppliers.data || []).map((s) => [s.id, s])), [allSuppliers.data]);
  const medicationById = useMemo(() => Object.fromEntries((allMedications.data || []).map((m) => [m.id, m])), [allMedications.data]);
  const itemById = useMemo(() => Object.fromEntries((allItems.data || []).map((i) => [i.id, i])), [allItems.data]);

  function updateLine(index, patch) {
    setLines(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)));
  }

  function addLine() {
    setLines([...lines, emptyLine()]);
  }

  function removeLine(index) {
    setLines(lines.filter((_, i) => i !== index));
  }

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const validLines = lines.filter((l) => l.ownerId && Number(l.quantityOrdered) > 0);
    if (!supplierId || validLines.length === 0) {
      setFormError(t('inventoryPage.errorLinesRequired'));
      return;
    }
    try {
      await createOrder.mutateAsync({
        supplierId,
        notes: notes.trim() || undefined,
        lines: validLines.map((l) => ({
          medicationId: l.lineType === 'medication' ? l.ownerId : undefined,
          inventoryItemId: l.lineType === 'inventoryItem' ? l.ownerId : undefined,
          quantityOrdered: Number(l.quantityOrdered),
          unitCost: l.unitCost === '' ? undefined : Number(l.unitCost),
        })),
      });
      setSupplierId('');
      setNotes('');
      setLines([emptyLine()]);
    } catch (err) {
      setFormError(err.message || t('inventoryPage.errorCreateOrder'));
    }
  }

  function lineOwnerName(line) {
    if (line.medicationId) return medicationById[line.medicationId]?.name || line.medicationId;
    if (line.inventoryItemId) return itemById[line.inventoryItemId]?.name || line.inventoryItemId;
    return '—';
  }

  const columns = [
    { key: 'supplier', header: t('inventoryPage.supplier'), accessor: (o) => supplierById[o.order.supplierId]?.name || o.order.supplierId, sortable: true, className: 'font-semibold' },
    { key: 'status', header: t('referralsPage.status'), accessor: (o) => o.order.status, sortable: true, render: (o) => <StatusPill status={o.order.status} /> },
    { key: 'orderedAt', header: t('inventoryPage.orderedAt'), accessor: (o) => o.order.orderedAt, sortable: true, render: (o) => formatDateTime(o.order.orderedAt) },
  ];

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.inventory.purchaseOrders')} />

      <form onSubmit={handleCreate} className="mb-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <div className="flex flex-wrap items-end gap-3">
          <Field label={t('inventoryPage.supplier')}>
            <select value={supplierId} onChange={(e) => setSupplierId(e.target.value)} className={`${inputClass} w-56`}>
              <option value="">{t('inventoryPage.selectSupplier')}</option>
              {(suppliers.data || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
          <Field label={t('pharmacistPage.notesOptional')}>
            <input value={notes} onChange={(e) => setNotes(e.target.value)} className={`${inputClass} w-64`} />
          </Field>
        </div>

        <div className="flex flex-col gap-2">
          {lines.map((line, index) => (
            <div key={index} className="flex flex-wrap items-end gap-3 rounded-lg bg-slate-50 p-3">
              <Field label={t('inventoryPage.lineType')}>
                <select
                  value={line.lineType}
                  onChange={(e) => updateLine(index, { lineType: e.target.value, ownerId: '' })}
                  className={`${inputClass} w-40`}
                >
                  <option value="medication">{t('inventoryPage.lineTypeMedication')}</option>
                  <option value="inventoryItem">{t('inventoryPage.lineTypeInventoryItem')}</option>
                </select>
              </Field>
              <Field label={line.lineType === 'medication' ? t('inventoryPage.selectMedication') : t('inventoryPage.selectInventoryItem')}>
                <select value={line.ownerId} onChange={(e) => updateLine(index, { ownerId: e.target.value })} className={`${inputClass} w-56`}>
                  <option value="">{t('booking.select')}</option>
                  {(line.lineType === 'medication' ? medications.data || [] : items.data || []).map((o) => (
                    <option key={o.id} value={o.id}>{o.name}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('pharmacistPage.quantity')}>
                <input type="number" min="1" value={line.quantityOrdered} onChange={(e) => updateLine(index, { quantityOrdered: e.target.value })} className={`${inputClass} w-24`} />
              </Field>
              <Field label={t('inventoryPage.unitCostOptional')}>
                <input type="number" min="0" step="0.01" value={line.unitCost} onChange={(e) => updateLine(index, { unitCost: e.target.value })} className={`${inputClass} w-28`} />
              </Field>
              {lines.length > 1 && (
                <button type="button" onClick={() => removeLine(index)} className="text-sm text-danger hover:underline">
                  {t('inventoryPage.removeLine')}
                </button>
              )}
            </div>
          ))}
          <button type="button" onClick={addLine} className="self-start text-sm text-brand-text hover:underline">
            + {t('inventoryPage.addLine')}
          </button>
        </div>

        <Button type="submit" variant="accent" className="self-start" disabled={createOrder.isPending}>
          {createOrder.isPending ? t('common.adding') : t('inventoryPage.createOrder')}
        </Button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      <DataTable
        columns={columns}
        rows={orders || []}
        rowKey={(o) => o.order.id}
        defaultSortKey="orderedAt"
        defaultSortDir="desc"
        renderExpanded={(o) => <OrderLines order={o.order} lines={o.lines} lineOwnerName={lineOwnerName} />}
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('inventoryPage.emptyOrdersTitle')}
        emptyDescription={t('inventoryPage.emptyOrdersDescription')}
      />
    </PageContainer>
  );
}

function OrderLines({ order, lines, lineOwnerName }) {
  const { t } = useTranslation();
  const receiveOrder = useReceivePurchaseOrder(order.id);
  const cancelOrder = useCancelPurchaseOrder(order.id);
  const [actionError, setActionError] = useState(null);

  async function handleReceive() {
    setActionError(null);
    try {
      await receiveOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('inventoryPage.errorReceiveOrder'));
    }
  }

  async function handleCancel() {
    setActionError(null);
    try {
      await cancelOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('inventoryPage.errorCancelOrder'));
    }
  }

  return (
    <div>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('inventoryPage.lines')}</p>
      <table className="mb-3 w-full text-left text-sm">
        <thead>
          <tr className="text-xs uppercase tracking-wide text-ink-muted">
            <th className="py-1 pr-4 font-semibold">{t('common.name')}</th>
            <th className="py-1 pr-4 font-semibold">{t('inventoryPage.quantityOrdered')}</th>
            <th className="py-1 font-semibold">{t('inventoryPage.unitCost')}</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.id} className="border-t border-slate-100">
              <td className="py-1.5 pr-4">{lineOwnerName(line)}</td>
              <td className="py-1.5 pr-4 font-mono">{line.quantityOrdered}</td>
              <td className="py-1.5 font-mono">{line.unitCost != null ? formatCurrency(line.unitCost) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {order.notes && <p className="mb-3 text-sm text-ink-muted">{order.notes}</p>}

      {order.status === 'ordered' && (
        <div className="flex items-center gap-3 text-sm">
          <Button type="button" variant="accent" size="sm" onClick={handleReceive} disabled={receiveOrder.isPending}>
            {t('inventoryPage.receiveOrder')}
          </Button>
          <Button type="button" variant="danger" size="sm" onClick={handleCancel} disabled={cancelOrder.isPending}>
            {t('inventoryPage.cancelOrder')}
          </Button>
        </div>
      )}
      {actionError && <div className="mt-3"><ErrorBanner message={actionError} /></div>}
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
