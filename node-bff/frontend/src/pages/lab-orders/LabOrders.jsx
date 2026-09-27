import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useLabOrders,
  useLabOrderRequests,
  useCreateLabOrder,
  usePatients,
  useProviders,
  useLabRates,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import LabOrderTestsEditor, { emptyTestLine } from '../../components/labOrder/LabOrderTestsEditor.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const emptyForm = { patientId: '', orderingProviderId: '', priority: 'routine', notes: '', consentAcknowledged: false };

/**
 * Staff lab-order list + create form - GET/POST /api/lab-orders
 * (LabOrderController), plus a review-queue banner for patient-initiated
 * requests (GET /api/lab-orders/requests) linking into LabOrderDetail's
 * own confirm-and-order form. Same list+create+pending-queue shape as the
 * reference project's cargo/Waybills.jsx, this app's own fields. The
 * status filter narrows `rows` before they reach DataTable, alongside its
 * own new search box and sortable columns (modern-UI redesign).
 */
export default function LabOrders() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [statusFilter, setStatusFilter] = useState('');
  const { data: orders, isLoading, isError, error, refetch } = useLabOrders(true);
  const { data: requests } = useLabOrderRequests(true);
  const { data: patients } = usePatients();
  const { data: providers } = useProviders(true, 'active');
  const { data: labRates } = useLabRates(true);
  const createOrder = useCreateLabOrder();

  const patientById = useMemo(() => Object.fromEntries((patients || []).map((p) => [p.id, p])), [patients]);
  const providerById = useMemo(() => Object.fromEntries((providers || []).map((p) => [p.id, p])), [providers]);

  function patientName(order) {
    const p = patientById[order.patientId];
    return p ? `${p.firstName} ${p.lastName}` : '';
  }
  function providerName(order) {
    return providerById[order.orderingProviderId]?.fullName || '';
  }

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [lines, setLines] = useState([emptyTestLine()]);
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const tests = lines.filter((l) => l.testCode.trim() && l.testName.trim());
    if (!form.patientId || !form.orderingProviderId || tests.length === 0) {
      setFormError(t('labOrdersPage.errorFields'));
      return;
    }
    try {
      await createOrder.mutateAsync({
        patientId: form.patientId,
        orderingProviderId: form.orderingProviderId,
        notes: form.notes.trim() || undefined,
        priority: form.priority,
        consentAcknowledged: form.consentAcknowledged,
        tests: tests.map((l) => ({
          testCode: l.testCode.trim(),
          testName: l.testName.trim(),
          specimenType: l.specimenType.trim() || undefined,
          notes: l.notes?.trim() || undefined,
        })),
      });
      setForm(emptyForm);
      setLines([emptyTestLine()]);
      setShowCreate(false);
    } catch (err) {
      setFormError(err.message || t('labOrdersPage.errorCreate'));
    }
  }

  const visibleOrders = (orders || []).filter((o) => !statusFilter || o.status === statusFilter);

  const columns = [
    { key: 'orderRef', header: t('appointmentDetail.reference'), accessor: (o) => o.orderRef, sortable: true, className: 'font-mono text-xs' },
    { key: 'patient', header: t('labOrdersPage.patient'), accessor: patientName, sortable: true },
    { key: 'provider', header: t('labOrdersPage.orderingProvider'), accessor: providerName, sortable: true },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (o) => o.status,
      sortable: true,
      render: (o) => <StatusPill status={o.status} />,
    },
    { key: 'orderedAt', header: t('common.bookedAt'), accessor: (o) => o.orderedAt, sortAccessor: (o) => new Date(o.orderedAt), sortable: true, render: (o) => formatDateTime(o.orderedAt) },
    { key: 'totalCost', header: t('appointmentTypesPage.price'), accessor: (o) => o.totalCost, sortable: true, className: 'font-mono font-semibold', render: (o) => formatCurrency(o.totalCost) },
  ];

  return (
    <PageContainer width="lg">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">{t('nav.provider.labOrders')}</h1>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label={t('labOrdersPage.filterByStatus')} className={`${inputClass} w-44`}>
          <option value="">{t('labOrdersPage.allStatuses')}</option>
          <option value="ordered">{t('status.ordered')}</option>
          <option value="specimen_collected">{t('status.specimen_collected')}</option>
          <option value="in_transit">{t('status.in_transit')}</option>
          <option value="resulted">{t('status.resulted')}</option>
          <option value="reviewed">{t('status.reviewed')}</option>
          <option value="cancelled">{t('status.cancelled')}</option>
        </select>
      </div>

      {requests && requests.length > 0 && (
        <div className="mb-6 rounded-xl border border-warning/40 bg-warning-light p-4">
          <h2 className="mb-3 text-sm font-semibold text-ink">{t('labOrdersPage.pendingRequests', { count: requests.length })}</h2>
          <div className="flex flex-col gap-2">
            {[...requests].sort((a, b) => new Date(a.orderedAt) - new Date(b.orderedAt)).map((r) => (
              <Link
                key={r.id}
                to={`/lab-orders/${r.id}`}
                className="flex items-center justify-between rounded-lg border border-slate-200 bg-surface p-3 hover:border-brand/40"
              >
                <div>
                  <span className="font-mono text-xs text-ink-muted">{r.orderRef}</span>
                  <p className="text-sm font-semibold text-ink">{patientById[r.patientId] ? `${patientById[r.patientId].firstName} ${patientById[r.patientId].lastName}` : '…'}</p>
                </div>
                <span className="text-sm font-medium text-brand-text">{t('labOrdersPage.review')} &rsaquo;</span>
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand-text hover:bg-brand-light/40"
        >
          {showCreate ? t('labOrdersPage.close') : t('labOrdersPage.newLabOrder')}
        </button>
      </div>

      {showCreate && (
        <>
          <form onSubmit={handleCreate} className="mb-4 rounded-xl border border-slate-200 bg-surface p-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label={t('labOrdersPage.patient')}>
                <select value={form.patientId} onChange={(e) => setForm({ ...form, patientId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(patients || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.firstName} {p.lastName}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('labOrdersPage.orderingProvider')}>
                <select value={form.orderingProviderId} onChange={(e) => setForm({ ...form, orderingProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(providers || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.fullName}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('labOrdersPage.priority')}>
                <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="routine">{t('labOrdersPage.routine')}</option>
                  <option value="urgent">{t('labOrdersPage.urgent')}</option>
                  <option value="stat">{t('labOrdersPage.stat')}</option>
                </select>
              </Field>
            </div>

            <div className="mt-4">
              <Field label={t('labOrdersPage.notesOptional')}>
                <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} />
              </Field>
            </div>

            <div className="mt-4">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
                {t('requestLabTest.tests')} {labRates && labRates.length > 0 && <span className="font-normal normal-case text-ink-muted">- {t('labOrdersPage.knownCodes', { codes: labRates.map((r) => r.testCode).join(', ') })}</span>}
              </span>
              <LabOrderTestsEditor lines={lines} onChange={setLines} labRates={labRates} />
            </div>

            <label className="mt-4 flex items-start gap-2 text-sm text-ink">
              <input type="checkbox" checked={form.consentAcknowledged} onChange={(e) => setForm({ ...form, consentAcknowledged: e.target.checked })} className="mt-0.5 h-4 w-4 rounded border-slate-300" />
              <span>{t('labOrdersPage.consentLabel')}</span>
            </label>

            <Button type="submit" variant="accent" className="mt-4" disabled={createOrder.isPending}>
              {createOrder.isPending ? t('labOrdersPage.creating') : t('labOrdersPage.createLabOrder')}
            </Button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
        </>
      )}

      <DataTable
        columns={columns}
        rows={visibleOrders}
        rowKey="id"
        searchAccessors={[(o) => o.orderRef, patientName, providerName]}
        onRowClick={(o) => navigate(`/lab-orders/${o.id}`)}
        defaultSortKey="orderedAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('myLabOrders.emptyTitle')}
        emptyDescription={t('labOrdersPage.emptyDescription')}
      />
    </PageContainer>
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
