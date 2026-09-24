import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTrackLabOrder } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import StatusPill from '../components/StatusPill.jsx';
import Skeleton from '../components/Skeleton.jsx';
import { formatDateTime } from '../lib/format.js';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Reachable without logging in - GET /api/lab-orders/track/{ref}?phone=
 * (permitAll()'d server-side, carved out of node-bff's session gate too).
 * Deliberately narrow (LabOrderTrackingView) - status/timestamps only,
 * never result values/reference ranges/abnormal flags/provider names, per
 * the phase-7 visibility decision. Ported from this app's own
 * TrackAppointment.jsx, same two-factor ref+phone shape.
 */
export default function TrackLabOrder() {
  const { t } = useTranslation();
  const [ref, setRef] = useState('');
  const [phone, setPhone] = useState('');
  const [submitted, setSubmitted] = useState(null);

  const trackQuery = useTrackLabOrder(submitted?.ref, submitted?.phone);

  function handleSubmit(event) {
    event.preventDefault();
    setSubmitted({ ref: ref.trim(), phone: phone.trim() });
  }

  const notFound = trackQuery.isError && trackQuery.error instanceof ApiError && trackQuery.error.status === 404;

  return (
    <div className="mx-auto max-w-md">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('publicNav.trackLabOrder')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('trackLabOrder.subtitle')}</p>

      <form onSubmit={handleSubmit} className="mb-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('trackLabOrder.reference')}</span>
          <input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="e.g. A1B2C3" className={`${inputClass} font-mono uppercase`} />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('trackLabOrder.phoneNumber')}</span>
          <input value={phone} onChange={(e) => setPhone(e.target.value)} className={inputClass} />
        </label>
        <button
          type="submit"
          disabled={!ref.trim() || !phone.trim()}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
        >
          {t('trackLabOrder.track')}
        </button>
      </form>

      {trackQuery.isLoading && <Skeleton className="h-32 w-full" />}

      {notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          {t('trackLabOrder.notFound')}
        </div>
      )}
      {trackQuery.isError && !notFound && (
        <div className="rounded-xl border border-danger/30 bg-danger-light px-4 py-3 text-sm text-danger">
          {trackQuery.error?.message || t('common.somethingWrong')}
        </div>
      )}

      {trackQuery.data && (
        <div className="rounded-xl border border-slate-200 bg-surface p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="font-mono text-xs text-ink-muted">{trackQuery.data.orderRef}</span>
            <StatusPill status={trackQuery.data.status} />
          </div>
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <div><dt className="text-ink-muted">{t('trackLabOrder.ordered')}</dt><dd className="text-ink">{formatDateTime(trackQuery.data.orderedAt)}</dd></div>
            <div><dt className="text-ink-muted">{t('trackLabOrder.specimenCollected')}</dt><dd className="text-ink">{formatDateTime(trackQuery.data.specimenCollectedAt)}</dd></div>
            <div><dt className="text-ink-muted">{t('trackLabOrder.sentToLab')}</dt><dd className="text-ink">{formatDateTime(trackQuery.data.sentAt)}</dd></div>
            <div><dt className="text-ink-muted">{t('trackLabOrder.resulted')}</dt><dd className="text-ink">{formatDateTime(trackQuery.data.resultedAt)}</dd></div>
            <div><dt className="text-ink-muted">{t('trackLabOrder.reviewed')}</dt><dd className="text-ink">{formatDateTime(trackQuery.data.reviewedAt)}</dd></div>
          </dl>
        </div>
      )}
    </div>
  );
}
