import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMySchedule, usePatients } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Card from '../../components/Card.jsx';
import TabGroup from '../../components/TabGroup.jsx';
import { formatTime } from '../../lib/format.js';

const SEEN_STATUSES = ['with_provider', 'checked_out'];

/**
 * Provider landing page - see GET /api/my-schedule (already day-scoped
 * server-side, ZoneOffset.UTC; now backed by AppointmentWorklistView for a
 * real startTime + guest contactName). Patient names for a patientId case
 * resolve via the same id->name Map pattern front-desk/Appointments.jsx
 * already established.
 */
export default function ProviderDashboard() {
  const { t } = useTranslation();
  const schedule = useMySchedule(true);
  const patients = usePatients();
  const { data, isLoading: scheduleLoading, isError: scheduleError, error, refetch } = schedule;
  const [seenFilter, setSeenFilter] = useState('all');

  const patientNames = useMemo(() => {
    const map = new Map();
    (patients.data || []).forEach((p) => map.set(p.id, `${p.firstName} ${p.lastName}`));
    return map;
  }, [patients.data]);

  const isLoading = scheduleLoading || patients.isLoading;
  const isError = scheduleError || patients.isError;

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
      <div className="grid gap-4 sm:grid-cols-2">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const seenCount = data.filter((a) => SEEN_STATUSES.includes(a.status)).length;
  const visible = data.filter((a) => {
    if (seenFilter === 'all') return true;
    const seen = SEEN_STATUSES.includes(a.status);
    return seenFilter === 'seen' ? seen : !seen;
  });

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-bold text-ink">{t('providerDashboard.title')}</h1>

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard label={t('providerDashboard.patientsToday')} value={data.length} />
        <StatCard
          label={t('providerDashboard.seenSoFar')}
          value={seenCount}
          hint={t('providerDashboard.remainingHint', { count: data.length - seenCount })}
        />
      </div>

      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm font-semibold text-ink">{t('providerDashboard.appointmentsToday')}</p>
          {data.length > 0 && (
            <TabGroup
              ariaLabel={t('common.filterByStatus')}
              compact
              value={seenFilter}
              onChange={setSeenFilter}
              options={[
                { value: 'all', label: t('providerDashboard.filterAll') },
                { value: 'notSeen', label: t('providerDashboard.filterNotSeen') },
                { value: 'seen', label: t('providerDashboard.filterSeen') },
              ]}
            />
          )}
        </div>
        {data.length === 0 ? (
          <EmptyState title={t('providerDashboard.emptyTitle')} description={t('providerDashboard.emptyDescription')} />
        ) : visible.length === 0 ? (
          <EmptyState title={t('common.noResults')} description={t('common.noResultsHint')} />
        ) : (
          <ul className="flex flex-col gap-2">
            {visible.map((a) => (
              <li key={a.id}>
                <Link
                  to={`/provider/appointments/${a.id}/encounter`}
                  className="flex items-center justify-between gap-3 rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                >
                  <div>
                    <p className="text-sm font-medium text-ink">
                      {(a.patientId && patientNames.get(a.patientId)) || a.contactName || t('frontDeskAppointments.guest')}
                    </p>
                    <p className="font-mono text-xs text-ink-muted">
                      {formatTime(a.startTime)} · {a.appointmentRef}
                    </p>
                  </div>
                  <StatusPill status={a.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
