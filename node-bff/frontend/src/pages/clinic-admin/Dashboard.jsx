import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useProviders,
  useRooms,
  useAppointmentTypes,
  useLabOrderRequests,
  useAppointments,
  useClinicAnalytics,
} from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ChartCard from '../../components/analytics/ChartCard.jsx';
import AppointmentVolumeChart from '../../components/analytics/AppointmentVolumeChart.jsx';
import RevenueChart from '../../components/analytics/RevenueChart.jsx';
import StatusBreakdownChart from '../../components/analytics/StatusBreakdownChart.jsx';
import ProviderUtilizationChart from '../../components/analytics/ProviderUtilizationChart.jsx';
import { formatCurrency } from '../../lib/format.js';

const ACTIVE_APPOINTMENT_STATUSES = new Set(['booked', 'checked_in', 'roomed', 'with_provider']);
const WINDOW_OPTIONS = [7, 30, 90];

/**
 * Clinic-admin landing page - summary counts composed from existing list
 * endpoints (providers/rooms/appointment-types already support
 * ?status=active, phase 5), plus an Analytics section (frontend phase O)
 * backed by the new GET /api/clinic/analytics aggregation endpoint -
 * genuinely new backend work, unlike the stat cards above which were
 * deliberately composed from data that already existed.
 */
export default function ClinicAdminDashboard() {
  const { t } = useTranslation();
  const providers = useProviders(true, 'active');
  const allProviders = useProviders(true);
  const rooms = useRooms(true, 'active');
  const appointmentTypes = useAppointmentTypes(true, 'active');
  const labOrderRequests = useLabOrderRequests(true);
  const appointments = useAppointments(true);

  const [windowDays, setWindowDays] = useState(30);
  const analytics = useClinicAnalytics(true, windowDays);

  const providerNameById = useMemo(
    () => Object.fromEntries((allProviders.data || []).map((p) => [p.id, p.fullName])),
    [allProviders.data],
  );

  const queries = [providers, rooms, appointmentTypes, labOrderRequests, appointments];
  const isLoading = queries.some((q) => q.isLoading);
  const isError = queries.some((q) => q.isError);

  if (isError) {
    return (
      <ErrorBanner
        message={queries.find((q) => q.isError)?.error?.message}
        onRetry={() => queries.forEach((q) => q.refetch())}
      />
    );
  }
  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, i) => (
          <Skeleton key={i} className="h-24 w-full" />
        ))}
      </div>
    );
  }

  const activeAppointmentCount = appointments.data.filter((a) => ACTIVE_APPOINTMENT_STATUSES.has(a.status)).length;
  const revenueTotal = (analytics.data?.revenue || []).reduce((sum, r) => sum + Number(r.total), 0);

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">{t('clinicAdminDashboard.title')}</h1>
        <Link to="/clinic-admin/settings" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          {t('clinicAdminDashboard.manageSettings')}
        </Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <Link to="/clinic-admin/providers" className="block transition-shadow hover:shadow-md">
          <StatCard label={t('clinicAdminDashboard.activeProviders')} value={providers.data.length} />
        </Link>
        <Link to="/clinic-admin/rooms" className="block transition-shadow hover:shadow-md">
          <StatCard label={t('clinicAdminDashboard.activeRooms')} value={rooms.data.length} />
        </Link>
        <Link to="/clinic-admin/appointment-types" className="block transition-shadow hover:shadow-md">
          <StatCard label={t('clinicAdminDashboard.appointmentTypes')} value={appointmentTypes.data.length} />
        </Link>
        <StatCard label={t('clinicAdminDashboard.activeAppointments')} value={activeAppointmentCount} />
        <Link to="/lab-orders" className="block transition-shadow hover:shadow-md">
          <StatCard
            label={t('clinicAdminDashboard.pendingLabRequests')}
            value={labOrderRequests.data.length}
            hint={t('clinicAdminDashboard.awaitingConfirm')}
          />
        </Link>
      </div>

      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-bold text-ink">{t('clinicAnalytics.sectionTitle')}</h2>
          <div className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group" aria-label={t('clinicAnalytics.windowLabel', { days: windowDays })}>
            {WINDOW_OPTIONS.map((d) => (
              <button
                key={d}
                type="button"
                onClick={() => setWindowDays(d)}
                aria-pressed={windowDays === d}
                className={`rounded-md px-2.5 py-1 transition-colors ${
                  windowDays === d ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
                }`}
              >
                {t(`clinicAnalytics.days${d}`)}
              </button>
            ))}
          </div>
        </div>

        {analytics.isError && (
          <ErrorBanner message={analytics.error?.message || t('clinicAnalytics.errorLoad')} onRetry={analytics.refetch} />
        )}

        {analytics.isLoading && (
          <div className="grid gap-4 lg:grid-cols-2">
            <Skeleton className="h-64 w-full" />
            <Skeleton className="h-64 w-full" />
          </div>
        )}

        {!analytics.isLoading && !analytics.isError && analytics.data && (
          <>
            {/* A single stat card - the 5-column grid the row above uses
                was leftover copy-paste and left 4 empty columns beside it
                at desktop width (real bug found in review, not a
                hypothetical). A plain max-width wrapper sizes it like one
                card from that row instead. */}
            <div className="max-w-xs">
              <StatCard
                label={t('clinicAdminDashboard.revenueWindow', { days: windowDays })}
                value={formatCurrency(revenueTotal)}
                hint={t('clinicAdminDashboard.totalHint', { count: analytics.data.appointmentVolume.reduce((s, d) => s + Number(d.total), 0) })}
                mono
              />
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <ChartCard title={t('clinicAnalytics.appointmentVolumeTitle')} subtitle={t('clinicAnalytics.appointmentVolumeSubtitle')}>
                <AppointmentVolumeChart data={analytics.data.appointmentVolume} />
              </ChartCard>
              <ChartCard title={t('clinicAnalytics.revenueTitle')} subtitle={t('clinicAnalytics.revenueSubtitle')}>
                <RevenueChart data={analytics.data.revenue} />
              </ChartCard>
              <ChartCard title={t('clinicAnalytics.statusBreakdownTitle')} subtitle={t('clinicAnalytics.statusBreakdownSubtitle')}>
                <StatusBreakdownChart data={analytics.data.statusBreakdown} />
              </ChartCard>
              <ChartCard title={t('clinicAnalytics.providerUtilizationTitle')} subtitle={t('clinicAnalytics.providerUtilizationSubtitle')}>
                <ProviderUtilizationChart data={analytics.data.appointmentsByProvider} providerNameById={providerNameById} />
              </ChartCard>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
