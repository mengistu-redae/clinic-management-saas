import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useTrackAppointment } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import StatusPill from '../components/StatusPill.jsx';
import Skeleton from '../components/Skeleton.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import { formatDateTime } from '../lib/format.js';
import PageContainer from '../components/PageContainer.jsx';
import { inputClass } from '../components/Field.jsx';

/**
 * Reachable without logging in - GET /api/appointments/track/{ref}?phone=
 * (permitAll()'d server-side, carved out of node-bff's session gate too).
 * Deliberately narrow (AppointmentTrackingView) - status/timestamps/
 * clinic/provider only, never clinical detail. Ported from the reference
 * project's TrackBooking.jsx.
 */
export default function TrackAppointment() {
  const { t } = useTranslation();
  const [ref, setRef] = useState('');
  const [phone, setPhone] = useState('');
  const [submitted, setSubmitted] = useState(null);

  const trackQuery = useTrackAppointment(submitted?.ref, submitted?.phone);

  function handleSubmit(event) {
    event.preventDefault();
    setSubmitted({ ref: ref.trim(), phone: phone.trim() });
  }

  const notFound = trackQuery.isError && trackQuery.error instanceof ApiError && trackQuery.error.status === 404;

  return (
    <PageContainer width="sm">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('publicNav.trackAppointment')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('trackAppointment.subtitle')}</p>

      <form onSubmit={handleSubmit} className="mb-6 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('trackLabOrder.reference')}</span>
          <input
            value={ref}
            onChange={(e) => setRef(e.target.value)}
            placeholder={t('trackLabOrder.referencePlaceholder')}
            className={`${inputClass} w-full font-mono uppercase`}
          />
        </label>
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('trackLabOrder.phoneNumber')}</span>
          <input value={phone} onChange={(e) => setPhone(e.target.value)} className={`${inputClass} w-full`} />
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

      {notFound && <ErrorBanner message={t('trackAppointment.notFound')} onRetry={() => trackQuery.refetch()} />}
      {trackQuery.isError && !notFound && (
        <ErrorBanner message={trackQuery.error?.message} onRetry={() => trackQuery.refetch()} />
      )}

      {trackQuery.data && (
        <div className="rounded-xl border border-slate-200 bg-surface p-5">
          <div className="mb-4 flex items-center justify-between">
            <span className="font-mono text-xs text-ink-muted">{trackQuery.data.appointmentRef}</span>
            <StatusPill status={trackQuery.data.status} />
          </div>
          <p className="mb-1 text-sm font-semibold text-ink">{trackQuery.data.clinicName}</p>
          <p className="text-xs text-ink-muted">{trackQuery.data.providerName}</p>
          <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
            <div>
              <dt className="text-ink-muted">{t('trackAppointment.scheduledFor')}</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.startTime)}</dd>
            </div>
            <div>
              <dt className="text-ink-muted">{t('common.bookedAt')}</dt>
              <dd className="text-ink">{formatDateTime(trackQuery.data.bookedAt)}</dd>
            </div>
          </dl>
        </div>
      )}
    </PageContainer>
  );
}
