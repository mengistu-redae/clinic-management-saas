import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
  useLabOrderInvoice,
  useGenerateLabOrderInvoice,
  useLabRates,
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import LabOrderTestsEditor from '../../components/labOrder/LabOrderTestsEditor.jsx';
import InvoicePanel from '../../components/InvoicePanel.jsx';
import PaymentsPanel from '../../components/PaymentsPanel.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

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
  const { t } = useTranslation();
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
  const invoiceQuery = useLabOrderInvoice(id);
  const generateInvoice = useGenerateLabOrderInvoice(id);

  const [actionError, setActionError] = useState(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [presentedId, setPresentedId] = useState('');

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);
  const [editLines, setEditLines] = useState([]);

  const [confirmForm, setConfirmForm] = useState(null);
  const [confirmLines, setConfirmLines] = useState([]);

  const [resultRows, setResultRows] = useState({});

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
      setActionError(t('labOrderDetail.errorAtLeastOneLine'));
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
      setActionError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleConfirmAndOrder(event) {
    event.preventDefault();
    setActionError(null);
    const cleanedLines = confirmLines.filter((l) => l.testCode.trim() && l.testName.trim());
    if (!confirmForm.orderingProviderId) {
      setActionError(t('labOrderDetail.errorPickProvider'));
      return;
    }
    if (cleanedLines.length === 0) {
      setActionError(t('labOrderDetail.errorNeedTestCode'));
      return;
    }
    try {
      await confirmAndOrder.mutateAsync({
        orderingProviderId: confirmForm.orderingProviderId,
        consentAcknowledged: confirmForm.consentAcknowledged,
        tests: cleanedLines.map((l) => ({ testCode: l.testCode.trim(), testName: l.testName.trim(), specimenType: l.specimenType.trim() || undefined, notes: l.notes?.trim() || undefined })),
      });
    } catch (err) {
      setActionError(err.message || t('labOrderDetail.errorConfirmOrder'));
    }
  }

  async function handleCollect() {
    setActionError(null);
    try {
      await collectSpecimen.mutateAsync(presentedId.trim() ? { presentedIdNumber: presentedId.trim() } : undefined);
      setPresentedId('');
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setActionError(err.message || t('labOrderDetail.errorIdMismatch'));
      } else {
        setActionError(err.message || t('labOrderDetail.errorCollect'));
      }
    }
  }

  async function handleSend() {
    setActionError(null);
    try {
      await sendOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('labOrderDetail.errorMarkSent'));
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
      setActionError(t('labOrderDetail.errorEnterResult'));
      return;
    }
    try {
      await resultOrder.mutateAsync({ results });
    } catch (err) {
      setActionError(err.message || t('labOrderDetail.errorRecordResults'));
    }
  }

  async function handleReview() {
    setActionError(null);
    try {
      await reviewOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('labOrderDetail.errorMarkReviewed'));
    }
  }

  async function handleCancel() {
    setActionError(null);
    try {
      await cancelOrder.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      setActionError(err.message || t('labOrderDetail.errorCancelOrder'));
      setConfirmingCancel(false);
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

  return (
    <PageContainer>
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">{t('myLabOrderDetail.title')}</h1>
          <p className="font-mono text-xs text-ink-muted">{order.orderRef}</p>
        </div>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{patientName}</p>
        <p className="text-sm text-ink-muted">
          {status === 'requested' ? t('labOrderDetail.noProviderYet') : provider ? t('labOrderDetail.orderedBy', { name: provider.fullName }) : '—'}
        </p>

        {editing ? (
          <div className="mt-4 border-t border-slate-100 pt-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label={t('labOrdersPage.orderingProvider')}>
                <select value={editForm.orderingProviderId} onChange={(e) => setEditForm({ ...editForm, orderingProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  {(providers || []).map((p) => (
                    <option key={p.id} value={p.id}>{p.fullName}</option>
                  ))}
                </select>
              </Field>
              <Field label={t('labOrdersPage.priority')}>
                <select value={editForm.priority} onChange={(e) => setEditForm({ ...editForm, priority: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="routine">{t('labOrdersPage.routine')}</option>
                  <option value="urgent">{t('labOrdersPage.urgent')}</option>
                  <option value="stat">{t('labOrdersPage.stat')}</option>
                </select>
              </Field>
            </div>
            <div className="mt-4">
              <Field label={t('referralsPage.notes')}><input value={editForm.notes} onChange={(e) => setEditForm({ ...editForm, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} /></Field>
            </div>
            <div className="mt-4">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('requestLabTest.tests')}</span>
              <LabOrderTestsEditor lines={editLines} onChange={setEditLines} labRates={labRates} />
            </div>
            <div className="mt-4 flex items-center gap-3">
              <Button type="button" variant="accent" onClick={saveEdit} disabled={updateOrder.isPending}>
                {updateOrder.isPending ? t('settingsPage.saving') : t('common.save')}
              </Button>
              <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">{t('common.cancel')}</button>
            </div>
          </div>
        ) : (
          <>
            <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
              <div><dt className="text-ink-muted">{t('labOrdersPage.priority')}</dt><dd className="capitalize text-ink">{t(`labOrdersPage.${order.priority}`, { defaultValue: order.priority })}</dd></div>
              <div><dt className="text-ink-muted">{t('invoicePanel.total')}</dt><dd className="font-mono font-semibold text-ink">{formatCurrency(order.totalCost)}</dd></div>
              <div><dt className="text-ink-muted">{t('trackLabOrder.ordered')}</dt><dd className="text-ink">{formatDateTime(order.orderedAt)}</dd></div>
              {order.specimenCollectedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.specimenCollected')}</dt><dd className="text-ink">{formatDateTime(order.specimenCollectedAt)}</dd></div>}
              {order.sentAt && <div><dt className="text-ink-muted">{t('labOrderDetail.sent')}</dt><dd className="text-ink">{formatDateTime(order.sentAt)}</dd></div>}
              {order.resultedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.resulted')}</dt><dd className="text-ink">{formatDateTime(order.resultedAt)}</dd></div>}
              {order.reviewedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.reviewed')}</dt><dd className="text-ink">{formatDateTime(order.reviewedAt)}</dd></div>}
            </dl>
            {order.notes && <p className="mt-3 text-sm text-ink-muted">{order.notes}</p>}

            {tests.length > 0 && (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-xs uppercase tracking-wide text-ink-muted">
                      <th className="pb-1 pr-3 font-semibold">{t('myLabOrderDetail.test')}</th>
                      <th className="pb-1 pr-3 font-semibold">{t('labOrderDetail.specimen')}</th>
                      <th className="pb-1 pr-3 font-semibold">{t('labOrderDetail.price')}</th>
                      {(status === 'resulted' || status === 'reviewed') && <th className="pb-1 font-semibold">{t('myLabOrderDetail.result')}</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {tests.map((t2) => (
                      <tr key={t2.id} className="border-t border-slate-100">
                        <td className="py-1 pr-3 text-ink">{t2.testName}{t2.testCode && <span className="ml-1 font-mono text-xs text-ink-muted">({t2.testCode})</span>}</td>
                        <td className="py-1 pr-3 text-ink-muted">{t2.specimenType || '—'}</td>
                        <td className="py-1 pr-3 text-ink">{t2.price != null ? formatCurrency(t2.price) : '—'}</td>
                        {(status === 'resulted' || status === 'reviewed') && (
                          <td className="py-1 text-ink">
                            {t2.resultValue ? (
                              <>
                                {t2.resultValue}{t2.resultUnit && ` ${t2.resultUnit}`}
                                {t2.referenceRange && <span className="ml-1 text-xs text-ink-muted">(ref: {t2.referenceRange})</span>}
                                {t2.abnormalFlag && <span className="ml-1 text-xs font-semibold text-danger">{t('myLabOrderDetail.abnormal')}</span>}
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
              <button type="button" onClick={startEdit} className="mt-4 text-sm text-brand-text hover:underline">{t('labOrderDetail.editOrder')}</button>
            )}
          </>
        )}
      </div>

      {status === 'requested' && confirmForm && (
        <form onSubmit={handleConfirmAndOrder} className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <h2 className="mb-3 text-sm font-semibold text-ink">{t('labOrderDetail.confirmAndOrderTitle')}</h2>
          <p className="mb-4 text-sm text-ink-muted">{t('labOrderDetail.confirmAndOrderDescription')}</p>
          <Field label={t('labOrdersPage.orderingProvider')}>
            <select value={confirmForm.orderingProviderId} onChange={(e) => setConfirmForm({ ...confirmForm, orderingProviderId: e.target.value })} className={`${inputClass} max-w-md`}>
              <option value="">{t('booking.select')}</option>
              {(providers || []).map((p) => (
                <option key={p.id} value={p.id}>{p.fullName}</option>
              ))}
            </select>
          </Field>
          <div className="mt-4">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {t('requestLabTest.tests')} {labRates && labRates.length > 0 && <span className="font-normal normal-case text-ink-muted">- {t('labOrdersPage.knownCodes', { codes: labRates.map((r) => r.testCode).join(', ') })}</span>}
            </span>
            <LabOrderTestsEditor lines={confirmLines} onChange={setConfirmLines} labRates={labRates} />
          </div>
          <label className="mt-4 flex items-start gap-2 text-sm text-ink">
            <input type="checkbox" checked={confirmForm.consentAcknowledged} onChange={(e) => setConfirmForm({ ...confirmForm, consentAcknowledged: e.target.checked })} className="mt-0.5 h-4 w-4 rounded border-slate-300" />
            <span>{t('labOrdersPage.consentLabel')}</span>
          </label>
          <Button type="submit" variant="accent" className="mt-4" disabled={confirmAndOrder.isPending}>
            {confirmAndOrder.isPending ? t('labOrderDetail.confirming') : t('labOrderDetail.confirmAndOrderBtn')}
          </Button>
        </form>
      )}

      {status === 'in_transit' && (
        <form onSubmit={handleResult} className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <h2 className="mb-3 text-sm font-semibold text-ink">{t('labOrderDetail.enterResultsTitle')}</h2>
          <div className="flex flex-col gap-4">
            {tests.map((t2) => (
              <div key={t2.id} className="rounded-lg border border-slate-100 p-3">
                <p className="mb-2 text-sm font-semibold text-ink">{t2.testName}</p>
                <div className="flex flex-wrap items-end gap-3">
                  <Field label={t('labOrderDetail.value')}>
                    <input value={resultRows[t2.id]?.value || ''} onChange={(e) => setResultRows({ ...resultRows, [t2.id]: { ...resultRows[t2.id], value: e.target.value } })} className={`${inputClass} w-32`} />
                  </Field>
                  <Field label={t('labOrderDetail.unit')}>
                    <input value={resultRows[t2.id]?.unit || ''} onChange={(e) => setResultRows({ ...resultRows, [t2.id]: { ...resultRows[t2.id], unit: e.target.value } })} className={`${inputClass} w-24`} />
                  </Field>
                  <Field label={t('labOrderDetail.referenceRange')}>
                    <input value={resultRows[t2.id]?.referenceRange || ''} onChange={(e) => setResultRows({ ...resultRows, [t2.id]: { ...resultRows[t2.id], referenceRange: e.target.value } })} className={`${inputClass} w-32`} />
                  </Field>
                  <label className="flex items-center gap-2 pb-2 text-sm text-ink">
                    <input type="checkbox" checked={Boolean(resultRows[t2.id]?.abnormalFlag)} onChange={(e) => setResultRows({ ...resultRows, [t2.id]: { ...resultRows[t2.id], abnormalFlag: e.target.checked } })} className="h-4 w-4 rounded border-slate-300" />
                    {t('labOrderDetail.abnormalLabel')}
                  </label>
                </div>
              </div>
            ))}
          </div>
          <Button type="submit" variant="accent" className="mt-4" disabled={resultOrder.isPending}>
            {resultOrder.isPending ? t('settingsPage.saving') : t('labOrderDetail.saveResults')}
          </Button>
        </form>
      )}

      {status !== 'requested' && status !== 'cancelled' && (
        <PaymentsPanel paymentsQuery={paymentsQuery} createPayment={createPayment} totalOwed={order.totalCost} />
      )}

      {status !== 'requested' && status !== 'cancelled' && (
        <InvoicePanel invoiceQuery={invoiceQuery} generateInvoice={generateInvoice} pdfUrl={`/api/lab-orders/${id}/invoice/pdf`} />
      )}

      {actionError && <div className="mt-4"><ErrorBanner message={actionError} /></div>}

      {!isTerminal && status !== 'requested' && (
        <div className="mt-5 flex flex-wrap items-center gap-3">
          {status === 'ordered' && (
            <div className="flex items-center gap-2 rounded-lg border border-slate-200 p-2">
              <input
                value={presentedId}
                onChange={(e) => setPresentedId(e.target.value)}
                placeholder={t('labOrderDetail.idPresentedPlaceholder')}
                aria-label={t('labOrderDetail.idPresentedPlaceholder')}
                className={`${inputClass} w-40`}
              />
              <Button type="button" onClick={handleCollect} disabled={collectSpecimen.isPending}>
                {collectSpecimen.isPending ? t('labOrderDetail.collecting') : t('labOrderDetail.collectSpecimen')}
              </Button>
            </div>
          )}
          {status === 'specimen_collected' && (
            <Button type="button" onClick={handleSend} disabled={sendOrder.isPending}>
              {sendOrder.isPending ? t('labOrderDetail.marking') : t('labOrderDetail.markSent')}
            </Button>
          )}
          {status === 'resulted' && (
            <Button type="button" onClick={handleReview} disabled={reviewOrder.isPending}>
              {reviewOrder.isPending ? t('labOrderDetail.marking') : t('labOrderDetail.markReviewed')}
            </Button>
          )}

          {status === 'ordered' && !confirmingCancel && (
            <button type="button" onClick={() => setConfirmingCancel(true)} className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light">
              {t('labOrderDetail.cancelOrder')}
            </button>
          )}
          {confirmingCancel && (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">{t('labOrderDetail.cancelConfirm')}</p>
              <button type="button" disabled={cancelOrder.isPending} onClick={handleCancel} className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50">
                {cancelOrder.isPending ? t('appointmentDetail.cancelling') : t('appointmentDetail.yesCancel')}
              </button>
              <button type="button" onClick={() => setConfirmingCancel(false)} className="shrink-0 text-sm text-ink-muted hover:underline">{t('appointmentDetail.neverMind')}</button>
            </div>
          )}
        </div>
      )}
    </PageContainer>
  );
}
