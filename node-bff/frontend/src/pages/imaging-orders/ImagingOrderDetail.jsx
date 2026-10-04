import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useImagingOrder,
  usePatient,
  useProviders,
  useUpdateImagingOrder,
  useScheduleImagingOrder,
  useStartImagingOrder,
  useCompleteImagingOrder,
  useReviewImagingOrder,
  useAcknowledgeCriticalImagingFinding,
  useCancelImagingOrder,
  useImagingOrderPayments,
  useCreateImagingOrderPayment,
  useImagingOrderInvoice,
  useGenerateImagingOrderInvoice,
} from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import InvoicePanel from '../../components/InvoicePanel.jsx';
import PaymentsPanel from '../../components/PaymentsPanel.jsx';
import ClaimsPanel from '../../components/ClaimsPanel.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Tabs from '../../components/Tabs.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

/**
 * Staff imaging-order detail - every ImagingOrderStatusService transition
 * plus payment.ImagingOrderPaymentController/invoice.ImagingOrderInvoiceController
 * (phase 42 backend). Shared by provider/clinic_admin/imaging_technologist,
 * mirroring LabOrderDetail.jsx's own canActOnOrder/canManageOrder split
 * exactly, but simpler in two real ways: no specimens/analyte-result
 * sub-resources (one study per order, no separate panels needed) and the
 * review form (findings/impression/criticalFinding) is inline here, not a
 * shared component, since ImagingOrder carries those fields directly.
 */
export default function ImagingOrderDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const { hasRole } = useAuth();
  const canActOnOrder = hasRole('imaging_technologist') || hasRole('clinic_admin');
  const canManageOrder = hasRole('provider') || hasRole('clinic_admin');
  const orderQuery = useImagingOrder(id);
  const order = orderQuery.data;

  const patientQuery = usePatient(order?.patientId);
  const { data: providers } = useProviders(true);
  const provider = order ? (providers || []).find((p) => p.id === order.orderingProviderId) : null;

  const updateOrder = useUpdateImagingOrder(id);
  const scheduleOrder = useScheduleImagingOrder(id);
  const startOrder = useStartImagingOrder(id);
  const completeOrder = useCompleteImagingOrder(id);
  const reviewOrder = useReviewImagingOrder(id);
  const acknowledgeCritical = useAcknowledgeCriticalImagingFinding(id);
  const cancelOrder = useCancelImagingOrder(id);
  const paymentsQuery = useImagingOrderPayments(id);
  const createPayment = useCreateImagingOrderPayment(id);
  const invoiceQuery = useImagingOrderInvoice(id);
  const generateInvoice = useGenerateImagingOrderInvoice(id);

  const [activeTab, setActiveTab] = useState('overview');
  const [actionError, setActionError] = useState(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [scheduledAt, setScheduledAt] = useState('');

  const [editing, setEditing] = useState(false);
  const [editForm, setEditForm] = useState(null);

  const [reviewForm, setReviewForm] = useState({ findings: '', impression: '', criticalFinding: false });

  function startEdit() {
    setActionError(null);
    setEditForm({
      orderingProviderId: order.orderingProviderId,
      modality: order.modality,
      studyType: order.studyType,
      studyCode: order.studyCode || '',
      priority: order.priority,
      notes: order.notes || '',
    });
    setEditing(true);
  }

  async function saveEdit() {
    setActionError(null);
    try {
      await updateOrder.mutateAsync({
        orderingProviderId: editForm.orderingProviderId,
        modality: editForm.modality,
        studyType: editForm.studyType.trim(),
        studyCode: editForm.studyCode.trim(),
        priority: editForm.priority,
        notes: editForm.notes.trim() || undefined,
      });
      setEditing(false);
    } catch (err) {
      setActionError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleSchedule() {
    setActionError(null);
    try {
      await scheduleOrder.mutateAsync(scheduledAt ? { scheduledAt: new Date(scheduledAt).toISOString() } : undefined);
      setScheduledAt('');
    } catch (err) {
      setActionError(err.message || t('imagingOrderDetail.errorSchedule'));
    }
  }

  async function handleStart() {
    setActionError(null);
    try {
      await startOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('imagingOrderDetail.errorStart'));
    }
  }

  async function handleComplete() {
    setActionError(null);
    try {
      await completeOrder.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('imagingOrderDetail.errorComplete'));
    }
  }

  async function handleReview(event) {
    event.preventDefault();
    setActionError(null);
    try {
      await reviewOrder.mutateAsync({
        findings: reviewForm.findings.trim() || undefined,
        impression: reviewForm.impression.trim() || undefined,
        criticalFinding: reviewForm.criticalFinding,
      });
    } catch (err) {
      setActionError(err.message || t('imagingOrderDetail.errorReview'));
    }
  }

  async function handleAcknowledgeCritical() {
    setActionError(null);
    try {
      await acknowledgeCritical.mutateAsync();
    } catch (err) {
      setActionError(err.message || t('imagingOrderDetail.errorAcknowledgeCritical'));
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
  const canCancel = status === 'ordered' || status === 'scheduled';

  return (
    <PageContainer>
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-ink">{t('imagingOrderDetail.title')}</h1>
          <p className="font-mono text-xs text-ink-muted">{order.orderRef}</p>
        </div>
        <StatusPill status={status} />
      </div>

      {order.criticalFinding && (
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/40 bg-danger-light p-4">
          <p className="text-sm font-semibold text-danger">{t('imagingOrderDetail.criticalFindingBanner')}</p>
          {order.criticalAcknowledgedAt ? (
            <span className="text-xs text-danger">{t('imagingOrderDetail.acknowledgedAt', { date: formatDateTime(order.criticalAcknowledgedAt) })}</span>
          ) : canManageOrder ? (
            <button
              type="button"
              disabled={acknowledgeCritical.isPending}
              onClick={handleAcknowledgeCritical}
              className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50"
            >
              {acknowledgeCritical.isPending ? t('imagingOrderDetail.acknowledging') : t('imagingOrderDetail.acknowledgeCritical')}
            </button>
          ) : null}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{patientName}</p>
        <p className="text-sm text-ink-muted">{provider ? t('labOrderDetail.orderedBy', { name: provider.fullName }) : '—'}</p>

        {editing ? (
          <div className="mt-4 border-t border-slate-100 pt-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
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
              <Field label={t('imagingOrdersPage.modality')}>
                <select value={editForm.modality} onChange={(e) => setEditForm({ ...editForm, modality: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="xray">{t('imagingOrdersPage.modalityXray')}</option>
                  <option value="ultrasound">{t('imagingOrdersPage.modalityUltrasound')}</option>
                  <option value="ct">{t('imagingOrdersPage.modalityCt')}</option>
                  <option value="mri">{t('imagingOrdersPage.modalityMri')}</option>
                  <option value="other">{t('imagingOrdersPage.modalityOther')}</option>
                </select>
              </Field>
            </div>
            <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label={t('imagingOrdersPage.studyType')}>
                <input value={editForm.studyType} onChange={(e) => setEditForm({ ...editForm, studyType: e.target.value })} className={`${inputClass} w-full`} />
              </Field>
              <Field label={t('imagingOrdersPage.studyCode')}>
                <input value={editForm.studyCode} onChange={(e) => setEditForm({ ...editForm, studyCode: e.target.value })} className={`${inputClass} w-full`} />
              </Field>
            </div>
            <div className="mt-4">
              <Field label={t('referralsPage.notes')}><input value={editForm.notes} onChange={(e) => setEditForm({ ...editForm, notes: e.target.value })} className={`${inputClass} w-full max-w-md`} /></Field>
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
              <div><dt className="text-ink-muted">{t('imagingOrdersPage.modality')}</dt><dd className="text-ink">{t(`imagingOrdersPage.modality${order.modality.charAt(0).toUpperCase()}${order.modality.slice(1)}`, { defaultValue: order.modality })}</dd></div>
              <div><dt className="text-ink-muted">{t('imagingOrdersPage.studyType')}</dt><dd className="text-ink">{order.studyType}{order.studyCode && <span className="ml-1 font-mono text-xs text-ink-muted">({order.studyCode})</span>}</dd></div>
              <div><dt className="text-ink-muted">{t('labOrdersPage.priority')}</dt><dd className="capitalize text-ink">{t(`labOrdersPage.${order.priority}`, { defaultValue: order.priority })}</dd></div>
              <div><dt className="text-ink-muted">{t('invoicePanel.total')}</dt><dd className="font-mono font-semibold text-ink">{formatCurrency(order.totalCost)}</dd></div>
              <div><dt className="text-ink-muted">{t('trackLabOrder.ordered')}</dt><dd className="text-ink">{formatDateTime(order.orderedAt)}</dd></div>
              {order.scheduledAt && <div><dt className="text-ink-muted">{t('status.scheduled')}</dt><dd className="text-ink">{formatDateTime(order.scheduledAt)}</dd></div>}
              {order.startedAt && <div><dt className="text-ink-muted">{t('status.in_progress')}</dt><dd className="text-ink">{formatDateTime(order.startedAt)}</dd></div>}
              {order.completedAt && <div><dt className="text-ink-muted">{t('status.completed')}</dt><dd className="text-ink">{formatDateTime(order.completedAt)}</dd></div>}
              {order.reviewedAt && <div><dt className="text-ink-muted">{t('trackLabOrder.reviewed')}</dt><dd className="text-ink">{formatDateTime(order.reviewedAt)}</dd></div>}
            </dl>
            {order.notes && <p className="mt-3 text-sm text-ink-muted">{order.notes}</p>}

            {canManageOrder && status === 'ordered' && (
              <button type="button" onClick={startEdit} className="mt-4 text-sm text-brand-text hover:underline">{t('labOrderDetail.editOrder')}</button>
            )}
          </>
        )}
      </div>

      {actionError && <div className="mt-4"><ErrorBanner message={actionError} /></div>}

      <Tabs
        tabs={[
          { key: 'overview', label: t('tabs.overview') },
          { key: 'study', label: t('tabs.study') },
          { key: 'billing', label: t('tabs.billing') },
        ]}
        active={activeTab}
        onChange={setActiveTab}
      />

      {activeTab === 'overview' && (
        <>
          {!isTerminal && (
            <div className="flex flex-wrap items-center gap-3">
              {canActOnOrder && status === 'ordered' && (
                <div className="flex items-center gap-2 rounded-lg border border-slate-200 p-2">
                  <input
                    type="datetime-local"
                    value={scheduledAt}
                    onChange={(e) => setScheduledAt(e.target.value)}
                    aria-label={t('imagingOrderDetail.scheduledAtOptional')}
                    className={`${inputClass} w-48`}
                  />
                  <Button type="button" onClick={handleSchedule} disabled={scheduleOrder.isPending}>
                    {scheduleOrder.isPending ? t('labOrderDetail.marking') : t('imagingOrderDetail.markScheduled')}
                  </Button>
                </div>
              )}
              {canActOnOrder && (status === 'ordered' || status === 'scheduled') && (
                <Button type="button" onClick={handleStart} disabled={startOrder.isPending}>
                  {startOrder.isPending ? t('labOrderDetail.marking') : t('imagingOrderDetail.markStarted')}
                </Button>
              )}
              {canActOnOrder && status === 'in_progress' && (
                <Button type="button" onClick={handleComplete} disabled={completeOrder.isPending}>
                  {completeOrder.isPending ? t('labOrderDetail.marking') : t('imagingOrderDetail.markCompleted')}
                </Button>
              )}

              {canManageOrder && canCancel && !confirmingCancel && (
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
        </>
      )}

      {activeTab === 'study' && (
        <>
          {canManageOrder && status === 'completed' && (
            <form onSubmit={handleReview} className="rounded-xl border border-slate-200 bg-surface p-5">
              <h2 className="mb-3 text-sm font-semibold text-ink">{t('imagingOrderDetail.reviewTitle')}</h2>
              <div className="flex flex-col gap-4">
                <Field label={t('imagingOrderDetail.findings')}>
                  <textarea value={reviewForm.findings} onChange={(e) => setReviewForm({ ...reviewForm, findings: e.target.value })} rows={4} className={`${inputClass} w-full`} />
                </Field>
                <Field label={t('imagingOrderDetail.impression')}>
                  <textarea value={reviewForm.impression} onChange={(e) => setReviewForm({ ...reviewForm, impression: e.target.value })} rows={2} className={`${inputClass} w-full`} />
                </Field>
                <label className="flex items-center gap-2 text-sm text-ink">
                  <input type="checkbox" checked={reviewForm.criticalFinding} onChange={(e) => setReviewForm({ ...reviewForm, criticalFinding: e.target.checked })} className="h-4 w-4 rounded border-slate-300" />
                  {t('imagingOrderDetail.flagCriticalFinding')}
                </label>
              </div>
              <Button type="submit" variant="accent" className="mt-4" disabled={reviewOrder.isPending}>
                {reviewOrder.isPending ? t('labOrderDetail.marking') : t('imagingOrderDetail.markReviewed')}
              </Button>
            </form>
          )}

          {(status === 'reviewed') && (order.findings || order.impression) && (
            <div className="rounded-xl border border-slate-200 bg-surface p-5">
              <h2 className="mb-3 text-sm font-semibold text-ink">{t('imagingOrderDetail.reviewTitle')}</h2>
              {order.findings && (
                <div className="mb-3">
                  <dt className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('imagingOrderDetail.findings')}</dt>
                  <dd className="mt-1 whitespace-pre-wrap text-sm text-ink">{order.findings}</dd>
                </div>
              )}
              {order.impression && (
                <div>
                  <dt className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('imagingOrderDetail.impression')}</dt>
                  <dd className="mt-1 whitespace-pre-wrap text-sm text-ink">{order.impression}</dd>
                </div>
              )}
            </div>
          )}

          {status !== 'reviewed' && status !== 'completed' && (
            <p className="text-sm text-ink-muted">{t('imagingOrderDetail.studyTabEmpty')}</p>
          )}
        </>
      )}

      {activeTab === 'billing' && (
        <>
          {status !== 'cancelled' && (
            <PaymentsPanel paymentsQuery={paymentsQuery} createPayment={createPayment} totalOwed={order.totalCost} />
          )}

          {status !== 'cancelled' && (
            <InvoicePanel invoiceQuery={invoiceQuery} generateInvoice={generateInvoice} pdfUrl={`/api/imaging-orders/${id}/invoice/pdf`} />
          )}

          {status !== 'cancelled' && invoiceQuery.data && order.patientId && (
            <ClaimsPanel invoiceId={invoiceQuery.data.id} patientId={order.patientId} />
          )}
        </>
      )}
    </PageContainer>
  );
}
