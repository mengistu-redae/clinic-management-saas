import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyAppointment, useClinicsDirectory, useClinicProviders, useClinicAppointmentTypes, useCancelMyAppointment } from '../api/queries.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { ApiError } from '../api/client.js';
import StatusPill from '../components/StatusPill.jsx';
import Skeleton from '../components/Skeleton.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import PageContainer from '../components/PageContainer.jsx';
import { formatDateTime } from '../lib/format.js';
import { useActiveClinicZone } from '../theme/TimezoneProvider.jsx';

/**
 * Reachable logged-in or as a guest, mirroring the reference project's
 * BookingDetail.jsx: a guest only ever sees this page immediately after
 * booking (via location.state) - a revisit/refresh has no session to
 * fetch by, so it dead-ends to /track-appointment instead. A signed-in
 * patient always re-fetches from the ownership-scoped GET (the source of
 * truth cancel/reschedule act against), using location.state only for the
 * instant first paint right after booking.
 */
export default function AppointmentDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const location = useLocation();
  const { authenticated, hasRole } = useAuth();

  const stateData = location.state;
  const appointmentQuery = useMyAppointment(authenticated ? id : undefined);
  const appointment = appointmentQuery.data || stateData?.appointment;

  const clinicsQuery = useClinicsDirectory();
  const clinicId = appointment?.tenantId;
  const providersQuery = useClinicProviders(clinicId);
  const typesQuery = useClinicAppointmentTypes(clinicId);

  const clinic = clinicsQuery.data?.find((c) => c.id === clinicId);
  const clinicName = stateData?.clinicName || clinic?.name;
  const providerName = stateData?.providerName || providersQuery.data?.find((p) => p.id === appointment?.providerId)?.fullName;
  const typeName = stateData?.typeName || typesQuery.data?.find((type) => type.id === appointment?.appointmentTypeId)?.name;
  const startTime = stateData?.slot?.startTime || appointment?.startTime;
  useActiveClinicZone(clinic?.timezone);

  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelError, setCancelError] = useState(null);
  const cancelAppointment = useCancelMyAppointment(id);

  async function handleCancel() {
    setCancelError(null);
    try {
      await cancelAppointment.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setCancelError(t('appointmentDetail.alreadyCancelled'));
      } else {
        setCancelError(err.message || t('appointmentDetail.errorCancel'));
      }
      setConfirmingCancel(false);
    }
  }

  if (appointmentQuery.isLoading && !stateData?.appointment) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (appointmentQuery.isError && !stateData?.appointment) {
    return <ErrorBanner message={appointmentQuery.error?.message} onRetry={appointmentQuery.refetch} />;
  }
  if (!appointment) {
    return !authenticated ? (
      <PageContainer width="xl" className="rounded-xl border border-slate-200 bg-surface p-5 text-sm text-ink-muted">
        {t('appointmentDetail.notAvailableGuest')}{' '}
        <Link to="/track-appointment" className="text-brand-text hover:underline">
          {t('publicNav.trackAppointment')}
        </Link>
        .
      </PageContainer>
    ) : (
      <ErrorBanner message={t('appointmentDetail.notFound')} />
    );
  }

  const status = cancelAppointment.data?.status || appointment.status;

  return (
    <PageContainer width="xl">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">{t('appointmentDetail.title')}</h1>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{clinicName || t('appointmentDetail.clinicFallback')}</p>
        <p className="text-sm text-ink-muted">
          {typeName || t('appointmentDetail.appointmentFallback')} with {providerName || t('appointmentDetail.providerFallback')}
        </p>

        <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
          <div>
            <dt className="text-ink-muted">{t('appointmentDetail.reference')}</dt>
            <dd className="font-mono text-xs text-ink">{appointment.appointmentRef || '—'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('appointmentDetail.when')}</dt>
            <dd className="text-ink">{startTime ? formatDateTime(startTime) : '—'}</dd>
          </div>
        </dl>
      </div>

      {!authenticated && (
        <div className="mt-4 rounded-lg border border-slate-200 bg-surface p-3 text-sm text-ink-muted">
          {t('appointmentDetail.bookedWithoutAccountPrefix')}{' '}
          <span className="font-mono text-ink">{appointment.appointmentRef}</span> {t('appointmentDetail.bookedWithoutAccountSuffix')}{' '}
          <Link to="/track-appointment" className="text-brand-text hover:underline">
            {t('publicNav.trackAppointment')}
          </Link>
          .
        </div>
      )}

      {cancelError && (
        <div className="mt-4">
          <ErrorBanner message={cancelError} />
        </div>
      )}

      {/* No guest self-service endpoint exists server-side - cancel/reschedule are patient-only, ownership-scoped actions. */}
      {authenticated && hasRole('patient') && status !== 'cancelled' && (
        <div className="mt-5 flex items-center gap-3">
          <Link
            to={`/appointments/${id}/reschedule`}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink hover:bg-slate-50"
          >
            {t('appointmentDetail.reschedule')}
          </Link>
          {!confirmingCancel ? (
            <button
              type="button"
              onClick={() => setConfirmingCancel(true)}
              className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light"
            >
              {t('appointmentDetail.cancelAppointment')}
            </button>
          ) : (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">{t('appointmentDetail.cancelConfirm')}</p>
              <button
                type="button"
                disabled={cancelAppointment.isPending}
                onClick={handleCancel}
                className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50"
              >
                {cancelAppointment.isPending ? t('appointmentDetail.cancelling') : t('appointmentDetail.yesCancel')}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingCancel(false)}
                className="shrink-0 text-sm text-ink-muted hover:underline"
              >
                {t('appointmentDetail.neverMind')}
              </button>
            </div>
          )}
        </div>
      )}
    </PageContainer>
  );
}
