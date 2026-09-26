import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppointmentsWorklist, usePatients } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatDateTime } from '../../lib/format.js';

const ACTIVE_STATUSES = ['booked', 'checked_in', 'roomed', 'with_provider'];

/**
 * Front-desk landing page - see GET /api/appointments/worklist (a real
 * startTime + guest contactName, unlike the plain tenant-wide list - see
 * AppointmentWorklistView's javadoc). Patient names for a patientId case
 * resolve via the same id->name Map pattern front-desk/Appointments.jsx
 * already established. "Active" counts (not yet checked_out/cancelled/
 * no_show) still stand in for a "today" view.
 */
export default function FrontDeskDashboard() {
  const { t } = useTranslation();
  const appointments = useAppointmentsWorklist(true);
  const patients = usePatients();
  const { data, isLoading: appointmentsLoading, isError: appointmentsError, error, refetch } = appointments;

  const patientNames = useMemo(() => {
    const map = new Map();
    (patients.data || []).forEach((p) => map.set(p.id, `${p.firstName} ${p.lastName}`));
    return map;
  }, [patients.data]);

  const isLoading = appointmentsLoading || patients.isLoading;
  const isError = appointmentsError || patients.isError;

  if (isError) {
    return (
      <ErrorBanner
        message={error?.message || patients.error?.message}
        onRetry={() => {
          refetch();
          patients.refetch();
        }}
      />
    );
  }
  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
    );
  }

  const byStatus = ACTIVE_STATUSES.reduce((acc, status) => {
    acc[status] = data.filter((a) => a.status === status).length;
    return acc;
  }, {});
  const activeCount = ACTIVE_STATUSES.reduce((sum, s) => sum + byStatus[s], 0);
  const recent = [...data].sort((a, b) => new Date(b.bookedAt) - new Date(a.bookedAt)).slice(0, 8);

  return (
    <div className="flex flex-col gap-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">{t('frontDeskDashboard.title')}</h1>
        <Link to="/front-desk/patients" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          {t('nav.frontDesk.bookWalkIn')}
        </Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label={t('frontDeskDashboard.activeAppointments')}
          value={activeCount}
          hint={t('frontDeskDashboard.totalHint', { count: data.length })}
        />
        <StatCard label={t('status.booked')} value={byStatus.booked} />
        <StatCard label={t('frontDeskDashboard.checkedInRoomed')} value={byStatus.checked_in + byStatus.roomed} />
        <StatCard label={t('frontDeskDashboard.withProvider')} value={byStatus.with_provider} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <p className="text-sm font-semibold text-ink">{t('frontDeskDashboard.recentAppointments')}</p>
          <Link to="/front-desk/appointments" className="text-xs font-medium text-brand-text hover:underline">
            {t('frontDeskDashboard.viewAll')}
          </Link>
        </div>
        {recent.length === 0 ? (
          <EmptyState title={t('frontDeskDashboard.emptyTitle')} description={t('frontDeskDashboard.emptyDescription')} />
        ) : (
          <ul className="flex flex-col gap-2">
            {recent.map((a) => (
              <li key={a.id}>
                <Link
                  to={`/front-desk/appointments/${a.id}`}
                  className="flex items-center justify-between gap-3 rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                >
                  <div>
                    <p className="text-sm font-medium text-ink">
                      {(a.patientId && patientNames.get(a.patientId)) || a.contactName || t('frontDeskAppointments.guest')}
                    </p>
                    <p className="font-mono text-xs text-ink-muted">
                      {a.appointmentRef} · {formatDateTime(a.startTime)}
                    </p>
                  </div>
                  <div className="flex flex-col items-end gap-1">
                    <span className="text-xs capitalize text-ink-muted">{t(`channel.${a.channel}`, { defaultValue: a.channel.replace(/_/g, ' ') })}</span>
                    <StatusPill status={a.status} />
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
