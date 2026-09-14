import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  useLabOrders,
  useLabOrderRequests,
  useCreateLabOrder,
  usePatients,
  useProviders,
  useLabRates,
} from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
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
 * reference project's cargo/Waybills.jsx, this app's own fields.
 */
export default function LabOrders() {
  const [statusFilter, setStatusFilter] = useState('');
  const { data: orders, isLoading, isError, error, refetch } = useLabOrders(true);
  const { data: requests } = useLabOrderRequests(true);
  const { data: patients } = usePatients();
  const { data: providers } = useProviders(true, 'active');
  const { data: labRates } = useLabRates(true);
  const createOrder = useCreateLabOrder();

  const patientById = useMemo(() => Object.fromEntries((patients || []).map((p) => [p.id, p])), [patients]);
  const providerById = useMemo(() => Object.fromEntries((providers || []).map((p) => [p.id, p])), [providers]);

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [lines, setLines] = useState([emptyTestLine()]);
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    const tests = lines.filter((l) => l.testCode.trim() && l.testName.trim());
    if (!form.patientId || !form.orderingProviderId || tests.length === 0) {
      setFormError('Patient, ordering provider, and at least one complete test line are required.');
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
      setFormError(err.message || 'Could not create this lab order - check every test code has a configured rate.');
    }
  }

  const visibleOrders = (orders || []).filter((o) => !statusFilter || o.status === statusFilter);
  const sortedOrders = [...visibleOrders].sort((a, b) => new Date(b.orderedAt) - new Date(a.orderedAt));

  return (
    <div>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">Lab Orders</h1>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status" className={`${inputClass} w-44`}>
          <option value="">All statuses</option>
          <option value="ordered">Ordered</option>
          <option value="specimen_collected">Specimen collected</option>
          <option value="in_transit">In transit</option>
          <option value="resulted">Resulted</option>
          <option value="reviewed">Reviewed</option>
          <option value="cancelled">Cancelled</option>
        </select>
      </div>

      {requests && requests.length > 0 && (
        <div className="mb-6 rounded-xl border border-warning/40 bg-warning-light p-4">
          <h2 className="mb-3 text-sm font-semibold text-ink">Pending patient requests ({requests.length})</h2>
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
                <span className="text-sm font-medium text-brand">Review &rsaquo;</span>
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand hover:bg-brand-light/40"
        >
          {showCreate ? 'Close' : '+ New lab order'}
        </button>
      </div>

      {showCreate && (
        <>
          <form onSubmit={handleCreate} className="mb-4 rounded-xl border border-slate-200 bg-surface p-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label="Patient">
                <select value={form.patientId} onChange={(e) => setForm({ ...form, patientId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">Select…</option>
                  {(patients || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.firstName} {p.lastName}</option>
                  ))}
                </select>
              </Field>
              <Field label="Ordering provider">
                <select value={form.orderingProviderId} onChange={(e) => setForm({ ...form, orderingProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">Select…</option>
                  {(providers || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.fullName}</option>
                  ))}
                </select>
              </Field>
              <Field label="Priority">
                <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="routine">Routine</option>
                  <option value="urgent">Urgent</option>
                  <option value="stat">Stat</option>
                </select>
              </Field>
            </div>

            <div className="mt-4">
              <Field label="Notes (optional)">
                <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} />
              </Field>
            </div>

            <div className="mt-4">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
                Tests {labRates && labRates.length > 0 && <span className="font-normal normal-case text-ink-muted">- known codes: {labRates.map((r) => r.testCode).join(', ')}</span>}
              </span>
              <LabOrderTestsEditor lines={lines} onChange={setLines} labRates={labRates} />
            </div>

            <label className="mt-4 flex items-start gap-2 text-sm text-ink">
              <input type="checkbox" checked={form.consentAcknowledged} onChange={(e) => setForm({ ...form, consentAcknowledged: e.target.checked })} className="mt-0.5 h-4 w-4 rounded border-slate-300" />
              <span>Patient consent acknowledged (required only for restricted/sensitive test codes)</span>
            </label>

            <button type="submit" disabled={createOrder.isPending} className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {createOrder.isPending ? 'Creating…' : 'Create lab order'}
            </button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
        </>
      )}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && sortedOrders.length === 0 && (
        <EmptyState title="No lab orders yet" description="Use “+ New lab order” above to create one - you'll need a lab rate configured for each test code." />
      )}

      {!isLoading && !isError && sortedOrders.length > 0 && (
        <div className="flex flex-col gap-2">
          {sortedOrders.map((order) => {
            const patient = patientById[order.patientId];
            const provider = providerById[order.orderingProviderId];
            return (
              <Link
                key={order.id}
                to={`/lab-orders/${order.id}`}
                className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
              >
                <div>
                  <div className="mb-1 flex items-center gap-2">
                    <StatusPill status={order.status} />
                    <span className="font-mono text-xs text-ink-muted">{order.orderRef}</span>
                  </div>
                  <p className="text-sm font-semibold text-ink">{patient ? `${patient.firstName} ${patient.lastName}` : '…'}</p>
                  <p className="text-xs text-ink-muted">
                    {provider ? provider.fullName : '—'} · {formatDateTime(order.orderedAt)}
                  </p>
                </div>
                <span className="font-mono text-sm font-semibold text-ink">{formatCurrency(order.totalCost)}</span>
              </Link>
            );
          })}
        </div>
      )}
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
