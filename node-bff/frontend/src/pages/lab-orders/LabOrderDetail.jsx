import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  useLabOrder,
  usePatient,
  useProviders,
  useUpdateLabOrder,
  useCollectSpecimen,
  useSendLabOrder,
  useResultLabOrder,
  useReviewLabOrder,
  useCancelLabOrder,
  useConfirmAndOrder,
  useLabOrderPayments,
  useCreateLabOrderPayment,
  useLabRates,
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import LabOrderTestsEditor from '../../components/labOrder/LabOrderTestsEditor.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Staff lab-order detail - one page covering every LabOrderStatusService
 * transition plus LabOrderCancellationController/payment.
 * LabOrderPaymentController, mirroring the reference project's own
 * cargo/WaybillDetail.jsx shape (a "requested" order swaps the normal
 * action UI for a confirm-and-order form, exactly like that page's own
 * confirm-and-issue). Shared by provider and clinic_admin alike (identical
 * backend permissions on every endpoint here).
 */
export default function LabOrderDetail() {
  const { id } = useParams();
  const orderQuery = useLabOrder(id);
  const resolved = orderQuery.data;
  const order = resolved?.order;
  const tests = resolved?.tests || [];

  const patientQuery = usePatient(order?.patientId);
  const { data: providers } = useProviders(true);
  const { data: labRates } = useLabRates(true);
  const provider = order ? (providers || []).find((p) => p.id === order.orderingProviderId) : null;

  const updateOrder = useUpdateLabOrder(id);
  const collectSpecimen = useCollectSpecimen(id);
  const sendOrder = useSendLabOrder(id);
  const resultOrder = useResultLabOrder(id);
  const reviewOrder = useReviewLabOrder(id);
  const cancelOrder = useCancelLabOrder(id);
  const confirmAndOrder = useConfirmAndOrder(id);
  const paymentsQuery = useLabOrderPayments(id);
  const createPayment = useCreateLabOrderPayment(id);

  const [actionError, setActionError] = useState(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [presentedId, setPresentedId] = useState('');

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [editLines, setEditLines] = useState([]);

  const [confirmForm, setConfirmForm] = useState(null);
  const [confirmLines, setConfirmLines] = useState([]);

  const [resultRows, setResultRows] = useState({});

  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('cash');
  const [paymentTxnId, setPaymentTxnId] = useState('');
  const [paymentError, setPaymentError] = useState(null);

  // Pre-fill the confirm-and-order form (provider picker + tests editor)
  // from the patient's own freeform request once it's loaded - same
  // one-shot auto-init the reference project's WaybillDetail.jsx uses for
  // its analogous "requested" state.
  useEffect(() => {
    if (order && order.status === 'requested' && confirmForm === null) {
      setConfirmForm({ orderingProviderId: '', consentAcknowledged: false });
      setConfirmLines(tests.map((t) => ({ testCode: t.testCode || '', testName: t.testName, specimenType: t.specimenType || '', notes: t.notes || '' })));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order?.id, order?.status]);

  useEffect(() => {
    if (order && order.status === 'in_transit') {
      const rows = {};
      tests.forEach((t) => {
        rows[t.id] = { value: t.resultValue || '', unit: t.resultUnit || '', referenceRange: t.referenceRange || '', abnormalFlag: Boolean(t.abnormalFlag) };
      });
      setResultRows(rows);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order?.id, order?.status]);

  function startEdit() {
    setActionError(null);
    setEditForm({ orderingProviderId: order.orderingProviderId, notes: order.notes || '', priority: order.priority });
    setEditLines(tests.map((t) => ({ testCode: t.testCode || '', testName: t.testName, specimenType: t.specimenType || '', notes: t.notes || '' })));
    setEditing(true);
  }

  async function saveEdit() {
    setActionError(null);
    const cleanedLines = editLines.filter((l) => l.testCode.trim() && l.testName.trim());
    if (cleanedLines.length === 0) {
      setActionError('At least one complete test line is required.');
      return;
    }
    try {
      await updateOrder.mutateAsync({
        orderingProviderId: editForm.orderingProviderId,
        notes: editForm.notes.trim() || undefined,
        priority: editForm.priority,
        tests: cleanedLines.map((l) => ({ testCode: l.testCode.trim(), testName: l.testName.trim(), specimenType: l.specimenType.trim() || undefined, notes: l.notes?.trim() || undefined })),
        consentAcknowledged: true,
      });
      setEditing(false);
    } catch (err) {
      setActionError(err.message || 'Could not save changes.');
    }
  }

  async function handleConfirmAndOrder(event) {
    event.preventDefault();
    setActionError(null);
    const cleanedLines = confirmLines.filter((l) => l.testCode.trim() && l.testName.trim());
    if (!confirmForm.orderingProviderId) {
      setActionError('Pick the ordering provider before confirming this order.');
      return;
    }
    if (cleanedLines.length === 0) {
      setActionError('At least one test needs a real test code assigned before pricing.');
      return;
    }
    try {
      await confirmAndOrder.mutateAsync({
        orderingProviderId: confirmForm.orderingProviderId,
        consentAcknowledged: confirmForm.consentAcknowledged,
        tests: cleanedLines.map((l) => ({ testCode: l.testCode.trim(), testName: l.testName.trim(), specimenType: l.specimenType.trim() || undefined, notes: l.notes?.trim() || undefined })),
      });
    } catch (err) {
      setActionError(err.message || 'Could not confirm and order - check every test code has a configured rate.');
    }
  }

  async function handleCollect() {
    setActionError(null);
    try {
      await collectSpecimen.mutateAsync(presentedId.trim() ? { presentedIdNumber: presentedId.trim() } : undefined);
      setPresentedId('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setActionError(err.message || "Presented ID doesn't match the ID on file.");
      } else {
        setActionError(err.message || 'Could not collect this specimen.');
      }
    }
  }

  async function handleSend() {
    setActionError(null);
    try {
      await sendOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || 'Could not mark this order sent.');
    }
  }

  async function handleResult(event) {
    event.preventDefault();
    setActionError(null);
    const results = tests
      .filter((t) => resultRows[t.id]?.value?.trim())
      .map((t) => ({
        labOrderTestId: t.id,
        value: resultRows[t.id].value.trim(),
        unit: resultRows[t.id].unit.trim() || undefined,
        referenceRange: resultRows[t.id].referenceRange.trim() || undefined,
        abnormalFlag: resultRows[t.id].abnormalFlag,
      }));
    if (results.length === 0) {
      setActionError('Enter at least one test result.');
      return;
    }
    try {
      await resultOrder.mutateAsync({ results });
    } catch (err) {
      setActionError(err.message || 'Could not record these results.');
    }
  }

  async function handleReview() {
    setActionError(null);
    try {
      await reviewOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || 'Could not mark this order reviewed.');
    }
  }

  async function handleCancel() {
    setActionError(null);
    try {
      await cancelOrder.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      setActionError(err.message || 'Could not cancel this order.');
      setConfirmingCancel(false);
    }
  }

  async function handleRecordPayment(event) {
    event.preventDefault();
    setPaymentError(null);
    const amount = Number(paymentAmount);
    if (!paymentAmount || Number.isNaN(amount) || amount <= 0) {
      setPaymentError('Enter a valid amount.');
      return;
    }
    try {
      await createPayment.mutateAsync({ method: paymentMethod, amount, transactionId: paymentTxnId.trim() || undefined });
      setPaymentAmount('');
      setPaymentTxnId('');
    } catch (err) {
      setPaymentError(err.message || 'Could not record this payment.');
    }
  }

  if (orderQuery.isLoading) {
    return <Skeleton className="h-64 w-full max-w-2xl" />;
  }
  if (orderQuery.isError) {
    return <ErrorBanner message={orderQuery.error?.message} onRetry={orderQuery.refetch} />;
  }

  const patientName = patientQuery.data ? `${patientQuery.data.firstName} ${patientQuery.data.lastName}` : '…';
  const status = order.status;
  const isTerminal = status === 'cancelled' || status === 'reviewed';
  const payments = paymentsQuery.data || [];
  const collected = payments.reduce((sum, p) => sum + Number(p.amount), 0);

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">Lab Order</h1>
          <p className="font-mono text-xs text-ink-muted">{order.orderRef}</p>
        </div>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{patientName}</p>
        <p className="text-sm text-ink-muted">
          {status === 'requested' ? 'No provider assigned yet' : provider ? `Ordered by ${provider.fullName}` : '—'}
        </p>

        {editing ? (
          <div className="mt-4 border-t border-slate-100 pt-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Ordering provider">
                <select value={editForm.orderingProviderId} onChange={(e) => setEditForm({ ...editForm, orderingProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  {(providers || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.fullName}</option>
                  ))}
                </select>
              </Field>
              <Field label="Priority">
                <select value={editForm.priority} onChange={(e) => setEditForm({ ...editForm, priority: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="routine">Routine</option>
                  <option value="urgent">Urgent</option>
                  <option value="stat">Stat</option>
                </select>
              </Field>
            </div>
            <div className="mt-4">
              <Field label="Notes"><input value={editForm.notes} onChange={(e) => setEditForm({ ...editForm, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} /></Field>
            </div>
            <div className="mt-4">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Tests</span>
              <LabOrderTestsEditor lines={editLines} onChange={setEditLines} labRates={labRates} />
            </div>
            <div className="mt-4 flex items-center gap-3">
              <button type="button" onClick={saveEdit} disabled={updateOrder.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
                {updateOrder.isPending ? 'Saving…' : 'Save'}
              </button>
              <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">Cancel</button>
            </div>
          </div>
        ) : (
          <>
            <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
              <div><dt className="text-ink-muted">Priority</dt><dd className="capitalize text-ink">{order.priority}</dd></div>
              <div><dt className="text-ink-muted">Total</dt><dd className="font-mono font-semibold text-ink">{formatCurrency(order.totalCost)}</dd></div>
              <div><dt className="text-ink-muted">Ordered</dt><dd className="text-ink">{formatDateTime(order.orderedAt)}</dd></div>
              {order.specimenCollectedAt && <div><dt className="text-ink-muted">Specimen collected</dt><dd className="text-ink">{formatDateTime(order.specimenCollectedAt)}</dd></div>}
              {order.sentAt && <div><dt className="text-ink-muted">Sent</dt><dd className="text-ink">{formatDateTime(order.sentAt)}</dd></div>}
              {order.resultedAt && <div><dt className="text-ink-muted">Resulted</dt><dd className="text-ink">{formatDateTime(order.resultedAt)}</dd></div>}
              {order.reviewedAt && <div><dt className="text-ink-muted">Reviewed</dt><dd className="text-ink">{formatDateTime(order.reviewedAt)}</dd></div>}
            </dl>
            {order.notes && <p className="mt-3 text-sm text-ink-muted">{order.notes}</p>}

            {tests.length > 0 && (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs uppercase tracking-wide text-ink-muted">
                      <th className="pb-1 pr-3 font-semibold">Test</th>
                      <th className="pb-1 pr-3 font-semibold">Specimen</th>
                      <th className="pb-1 pr-3 font-semibold">Price</th>
                      {(status === 'resulted' || status === 'reviewed') && <th className="pb-1 font-semibold">Result</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {tests.map((t) => (
                      <tr key={t.id} className="border-t border-slate-100">
                        <td className="py-1 pr-3 text-ink">{t.testName}{t.testCode && <span className="ml-1 font-mono text-xs text-ink-muted">({t.testCode})</span>}</td>
                        <td className="py-1 pr-3 text-ink-muted">{t.specimenType || '—'}</td>
                        <td className="py-1 pr-3 text-ink">{t.price != null ? formatCurrency(t.price) : '—'}</td>
                        {(status === 'resulted' || status === 'reviewed') && (
                          <td className="py-1 text-ink">
                            {t.resultValue ? (
                              <>
                                {t.resultValue}{t.resultUnit && ` ${t.resultUnit}`}
                                {t.referenceRange && <span className="ml-1 text-xs text-ink-muted">(ref: {t.referenceRange})</span>}
                                {t.abnormalFlag && <span className="ml-1 text-xs font-semibold text-danger">ABNORMAL</span>}
                              </>
                            ) : '—'}
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {status === 'ordered' && (
              <button type="button" onClick={startEdit} className="mt-4 text-sm text-brand hover:underline">Edit order</button>
            )}
          </>
        )}
      </div>

      {status === 'requested' && confirmForm && (
        <form onSubmit={handleConfirmAndOrder} className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <h2 className="mb-3 text-sm font-semibold text-ink">Confirm and order</h2>
          <p className="mb-4 text-sm text-ink-muted">
            Assign a real test code (and rate) to each requested test, pick the ordering provider, and confirm to turn
            this into a priced, ordered lab order.
          </p>
          <Field label="Ordering provider">
            <select value={confirmForm.orderingProviderId} onChange={(e) => setConfirmForm({ ...confirmForm, orderingProviderId: e.target.value })} className={`${inputClass} max-w-md`}>
              <option value="">Select…</option>
              {(providers || []).map((p) => (
                <option key={p.id} value={p.id}>{p.fullName}</option>
              ))}
            </select>
          </Field>
          <div className="mt-4">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
              Tests {labRates && labRates.length > 0 && <span className="font-normal normal-case text-ink-muted">- known codes: {labRates.map((r) => r.testCode).join(', ')}</span>}
            </span>
            <LabOrderTestsEditor lines={confirmLines} onChange={setConfirmLines} labRates={labRates} />
          </div>
          <label className="mt-4 flex items-start gap-2 text-sm text-ink">
            <input type="checkbox" checked={confirmForm.consentAcknowledged} onChange={(e) => setConfirmForm({ ...confirmForm, consentAcknowledged: e.target.checked })} className="mt-0.5 h-4 w-4 rounded border-slate-300" />
            <span>Patient consent acknowledged (required only for restricted/sensitive test codes)</span>
          </label>
          <button type="submit" disabled={confirmAndOrder.isPending} className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {confirmAndOrder.isPending ? 'Confirming…' : 'Confirm and order'}
          </button>
        </form>
      )}

      {status === 'in_transit' && (
        <form onSubmit={handleResult} className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <h2 className="mb-3 text-sm font-semibold text-ink">Enter results</h2>
          <div className="flex flex-col gap-4">
            {tests.map((t) => (
              <div key={t.id} className="rounded-lg border border-slate-100 p-3">
                <p className="mb-2 text-sm font-semibold text-ink">{t.testName}</p>
                <div className="flex flex-wrap items-end gap-3">
                  <Field label="Value">
                    <input value={resultRows[t.id]?.value || ''} onChange={(e) => setResultRows({ ...resultRows, [t.id]: { ...resultRows[t.id], value: e.target.value } })} className={`${inputClass} w-32`} />
                  </Field>
                  <Field label="Unit">
                    <input value={resultRows[t.id]?.unit || ''} onChange={(e) => setResultRows({ ...resultRows, [t.id]: { ...resultRows[t.id], unit: e.target.value } })} className={`${inputClass} w-24`} />
                  </Field>
                  <Field label="Reference range">
                    <input value={resultRows[t.id]?.referenceRange || ''} onChange={(e) => setResultRows({ ...resultRows, [t.id]: { ...resultRows[t.id], referenceRange: e.target.value } })} className={`${inputClass} w-32`} />
                  </Field>
                  <label className="flex items-center gap-2 pb-2 text-sm text-ink">
                    <input type="checkbox" checked={Boolean(resultRows[t.id]?.abnormalFlag)} onChange={(e) => setResultRows({ ...resultRows, [t.id]: { ...resultRows[t.id], abnormalFlag: e.target.checked } })} className="h-4 w-4 rounded border-slate-300" />
                    Abnormal
                  </label>
                </div>
              </div>
            ))}
          </div>
          <button type="submit" disabled={resultOrder.isPending} className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
            {resultOrder.isPending ? 'Saving…' : 'Save results'}
          </button>
        </form>
      )}

      {status !== 'requested' && status !== 'cancelled' && (
        <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-ink">Payments</h2>
            <span className="text-sm text-ink-muted">Collected {formatCurrency(collected)} of {formatCurrency(order.totalCost)}</span>
          </div>
          {payments.length > 0 && (
            <ul className="mb-4 flex flex-col gap-1.5 text-sm">
              {payments.map((p) => (
                <li key={p.id} className="flex items-center justify-between text-ink">
                  <span className="capitalize">{p.method}{p.transactionId && <span className="font-mono text-xs text-ink-muted"> ({p.transactionId})</span>}</span>
                  <span className="font-mono">{formatCurrency(p.amount)}</span>
                </li>
              ))}
            </ul>
          )}
          <form onSubmit={handleRecordPayment} className="flex flex-wrap items-end gap-3">
            <Field label="Method">
              <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)} className={inputClass}>
                <option value="cash">Cash</option>
                <option value="card">Card</option>
                <option value="mobile_money">Mobile money</option>
                <option value="insurance">Insurance</option>
              </select>
            </Field>
            <Field label="Amount">
              <input type="number" min="0" step="0.01" value={paymentAmount} onChange={(e) => setPaymentAmount(e.target.value)} className={`${inputClass} w-28`} />
            </Field>
            <Field label="Txn ID (optional)">
              <input value={paymentTxnId} onChange={(e) => setPaymentTxnId(e.target.value)} className={`${inputClass} w-40`} />
            </Field>
            <button type="submit" disabled={createPayment.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {createPayment.isPending ? 'Recording…' : 'Record payment'}
            </button>
          </form>
          {paymentError && <div className="mt-3"><ErrorBanner message={paymentError} /></div>}
        </div>
      )}

      {actionError && <div className="mt-4"><ErrorBanner message={actionError} /></div>}

      {!isTerminal && status !== 'requested' && (
        <div className="mt-5 flex flex-wrap items-center gap-3">
          {status === 'ordered' && (
            <div className="flex items-center gap-2 rounded-lg border border-slate-200 p-2">
              <input value={presentedId} onChange={(e) => setPresentedId(e.target.value)} placeholder="ID presented" className={`${inputClass} w-40`} />
              <button type="button" onClick={handleCollect} disabled={collectSpecimen.isPending} className="rounded-lg bg-brand px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
                {collectSpecimen.isPending ? 'Collecting…' : 'Collect specimen'}
              </button>
            </div>
          )}
          {status === 'specimen_collected' && (
            <button type="button" onClick={handleSend} disabled={sendOrder.isPending} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
              {sendOrder.isPending ? 'Marking…' : 'Mark sent'}
            </button>
          )}
          {status === 'resulted' && (
            <button type="button" onClick={handleReview} disabled={reviewOrder.isPending} className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50">
              {reviewOrder.isPending ? 'Marking…' : 'Mark reviewed'}
            </button>
          )}

          {status === 'ordered' && !confirmingCancel && (
            <button type="button" onClick={() => setConfirmingCancel(true)} className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light">
              Cancel order
            </button>
          )}
          {confirmingCancel && (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">Cancel this lab order?</p>
              <button type="button" disabled={cancelOrder.isPending} onClick={handleCancel} className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50">
                {cancelOrder.isPending ? 'Cancelling…' : 'Yes, cancel'}
              </button>
              <button type="button" onClick={() => setConfirmingCancel(false)} className="shrink-0 text-sm text-ink-muted hover:underline">Never mind</button>
            </div>
          )}
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
