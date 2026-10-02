import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useCollectSpecimenById,
  useMarkSpecimenInTransit,
  useReceiveSpecimen,
  useCompleteSpecimen,
  useRejectSpecimen,
  useSendSpecimenToReferenceLab,
} from '../api/queries.js';
import StatusPill from './StatusPill.jsx';
import ErrorBanner from './ErrorBanner.jsx';
import Skeleton from './Skeleton.jsx';
import { formatDateTime } from '../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-2 py-1.5 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * One specimen row plus whichever action its own current status allows -
 * a separate component (not inlined in the parent's own `.map`) purely to
 * keep each row's own local form-toggle state independent, same reasoning
 * PaymentsPanel.jsx's own PaymentRow split already documents.
 */
function SpecimenRow({ specimenId, orderId, specimen }) {
  const { t } = useTranslation();
  const collect = useCollectSpecimenById(orderId);
  const markInTransit = useMarkSpecimenInTransit(orderId);
  const receive = useReceiveSpecimen(orderId);
  const complete = useCompleteSpecimen(orderId);
  const reject = useRejectSpecimen(orderId);
  const sendToReferenceLab = useSendSpecimenToReferenceLab(orderId);

  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [sendingOut, setSendingOut] = useState(false);
  const [refLabName, setRefLabName] = useState(specimen.referenceLabName || '');
  const [refLabOrderNumber, setRefLabOrderNumber] = useState(specimen.referenceLabOrderNumber || '');
  const [turnaroundDays, setTurnaroundDays] = useState(specimen.expectedTurnaroundDays != null ? String(specimen.expectedTurnaroundDays) : '');
  const [error, setError] = useState(null);

  const status = specimen.status;
  const isTerminal = status === 'completed' || status === 'rejected';

  async function act(mutation, pendingMessageKey) {
    setError(null);
    try {
      await mutation.mutateAsync({ specimenId });
    } catch (err) {
      setError(err.message || t(pendingMessageKey));
    }
  }

  async function handleSendToReferenceLab(event) {
    event.preventDefault();
    setError(null);
    if (!refLabName.trim()) {
      setError(t('specimensPanel.errorReferenceLabNameRequired'));
      return;
    }
    try {
      await sendToReferenceLab.mutateAsync({
        specimenId,
        body: {
          referenceLabName: refLabName.trim(),
          referenceLabOrderNumber: refLabOrderNumber.trim() || undefined,
          expectedTurnaroundDays: turnaroundDays.trim() ? Number(turnaroundDays) : undefined,
        },
      });
      setSendingOut(false);
    } catch (err) {
      setError(err.message || t('specimensPanel.errorSendToReferenceLab'));
    }
  }

  async function handleReject(event) {
    event.preventDefault();
    setError(null);
    if (!reason.trim()) {
      setError(t('specimensPanel.errorReasonRequired'));
      return;
    }
    try {
      await reject.mutateAsync({ specimenId, body: { reason: reason.trim() } });
      setRejecting(false);
    } catch (err) {
      setError(err.message || t('specimensPanel.errorReject'));
    }
  }

  return (
    <li className="flex flex-col gap-2 border-b border-slate-100 pb-3 text-sm last:border-0 last:pb-0">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="font-semibold text-ink">{specimen.specimenType}</span>
          {specimen.rejectionReason && <p className="text-xs text-danger">{t('specimensPanel.rejectionReason', { reason: specimen.rejectionReason })}</p>}
          {status === 'sent_to_reference_lab' && (
            <p className="text-xs text-ink-muted">
              {t('specimensPanel.referenceLabSummary', {
                name: specimen.referenceLabName,
                order: specimen.referenceLabOrderNumber || '—',
                days: specimen.expectedTurnaroundDays != null ? specimen.expectedTurnaroundDays : '—',
              })}
            </p>
          )}
        </div>
        <StatusPill status={status} />
      </div>

      {!isTerminal && (
        <div className="flex flex-wrap items-center gap-2">
          {status === 'pending_collection' && (
            <button type="button" onClick={() => act(collect, 'specimensPanel.errorCollect')} disabled={collect.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
              {t('specimensPanel.collect')}
            </button>
          )}
          {status === 'collected' && (
            <button type="button" onClick={() => act(markInTransit, 'specimensPanel.errorMarkInTransit')} disabled={markInTransit.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
              {t('specimensPanel.markInTransit')}
            </button>
          )}
          {status === 'in_transit' && (
            <button type="button" onClick={() => act(receive, 'specimensPanel.errorReceive')} disabled={receive.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
              {t('specimensPanel.receive')}
            </button>
          )}
          {(status === 'received' || status === 'processing' || status === 'sent_to_reference_lab') && (
            <button type="button" onClick={() => act(complete, 'specimensPanel.errorComplete')} disabled={complete.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
              {t('specimensPanel.complete')}
            </button>
          )}
          {(status === 'collected' || status === 'in_transit' || status === 'received') && (
            <button type="button" onClick={() => setSendingOut((s) => !s)} className="text-xs font-semibold text-accent hover:underline">
              {t('specimensPanel.sendToReferenceLab')}
            </button>
          )}
          {status === 'sent_to_reference_lab' && (
            <button type="button" onClick={() => setSendingOut((s) => !s)} className="text-xs font-semibold text-accent hover:underline">
              {t('specimensPanel.editReferenceLabDetails')}
            </button>
          )}
          <button type="button" onClick={() => setRejecting((r) => !r)} className="text-xs font-semibold text-danger hover:underline">
            {t('specimensPanel.reject')}
          </button>
        </div>
      )}

      {sendingOut && (
        <form onSubmit={handleSendToReferenceLab} className="flex flex-wrap items-end gap-2 rounded-lg border border-slate-200 bg-slate-50 p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('specimensPanel.referenceLabName')}</span>
            <input value={refLabName} onChange={(e) => setRefLabName(e.target.value)} className={`${inputClass} w-40`} />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('specimensPanel.referenceLabOrderNumber')}</span>
            <input value={refLabOrderNumber} onChange={(e) => setRefLabOrderNumber(e.target.value)} className={`${inputClass} w-32`} />
          </label>
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('specimensPanel.expectedTurnaroundDays')}</span>
            <input type="number" min="0" value={turnaroundDays} onChange={(e) => setTurnaroundDays(e.target.value)} className={`${inputClass} w-20`} />
          </label>
          <button type="submit" disabled={sendToReferenceLab.isPending} className="rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
            {sendToReferenceLab.isPending ? t('settingsPage.saving') : t('common.save')}
          </button>
          <button type="button" onClick={() => setSendingOut(false)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {rejecting && (
        <form onSubmit={handleReject} className="flex flex-wrap items-end gap-2 rounded-lg border border-danger/30 bg-danger-light p-2">
          <label className="block">
            <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('specimensPanel.rejectReasonLabel')}</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} className={`${inputClass} w-48`} />
          </label>
          <button type="submit" disabled={reject.isPending} className="rounded-lg bg-danger px-3 py-1.5 text-xs font-semibold text-white hover:bg-danger/90 disabled:opacity-50">
            {reject.isPending ? t('settingsPage.saving') : t('specimensPanel.confirmReject')}
          </button>
          <button type="button" onClick={() => setRejecting(false)} className="text-xs text-ink-muted hover:underline">{t('common.cancel')}</button>
        </form>
      )}

      {specimen.collectedAt && <p className="text-xs text-ink-muted">{t('specimensPanel.collectedAt', { when: formatDateTime(specimen.collectedAt) })}</p>}

      {error && <ErrorBanner message={error} />}
    </li>
  );
}

/**
 * Lab module L1/L5 - a lab_technician/clinic_admin-only panel listing a
 * lab order's own specimens (one order can have more than one, e.g. a
 * blood+urine panel) with per-specimen action buttons matching whatever
 * its own current status allows. New in L8 - specimens had zero frontend
 * visibility before this phase, even though L1 built the full backend
 * lifecycle for them.
 */
export default function SpecimensPanel({ orderId, specimensQuery }) {
  const { t } = useTranslation();
  const specimens = specimensQuery.data || [];

  if (specimensQuery.isLoading) {
    return <Skeleton className="mt-5 h-24 w-full" />;
  }
  if (specimensQuery.isError) {
    return (
      <div className="mt-5">
        <ErrorBanner message={specimensQuery.error?.message} onRetry={specimensQuery.refetch} />
      </div>
    );
  }
  if (specimens.length === 0) {
    return null;
  }

  return (
    <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
      <h2 className="mb-3 text-sm font-semibold text-ink">{t('specimensPanel.title')}</h2>
      <ul className="flex flex-col gap-3">
        {specimens.map((s) => (
          <SpecimenRow key={s.id} specimenId={s.id} orderId={orderId} specimen={s} />
        ))}
      </ul>
    </div>
  );
}
