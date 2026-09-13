import { Link } from 'react-router-dom';
import { useAppointments } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';

const ACTIVE_STATUSES = ['booked', 'checked_in', 'roomed', 'with_provider'];

/**
 * Front-desk landing page - see GET /api/appointments (tenant-wide list).
 * "Active" counts (not yet checked_out/cancelled/no_show) stand in for a
 * "today" view - Appointment carries no slot start-time in its response
 * shape, so a real date-scoped worklist is deferred to the deep front-desk
 * phase (see the frontend plan's "Deliberate scope boundary" note).
 */
export default function FrontDeskDashboard() {
  const { data, isLoading, isError, error, refetch } = useAppointments(true);

  if (isError) {
    return <ErrorBanner message={error?.message} onRetry={refetch} />;
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
        <h1 className="text-2xl font-bold text-ink">Front Desk</h1>
        <Link to="/front-desk/patients" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          Book for a walk-in
        </Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="Active appointments" value={activeCount} hint={`${data.length} total`} />
        <StatCard label="Booked" value={byStatus.booked} />
        <StatCard label="Checked in / roomed" value={byStatus.checked_in + byStatus.roomed} />
        <StatCard label="With provider" value={byStatus.with_provider} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
        <div className="mb-3 flex items-center justify-between">
          <p className="text-sm font-semibold text-ink">Recent appointments</p>
          <Link to="/front-desk/appointments" className="text-xs font-medium text-brand hover:underline">
            View all
          </Link>
        </div>
        {recent.length === 0 ? (
          <EmptyState title="No appointments yet" description="Bookings for this clinic will show up here." />
        ) : (
          <ul className="flex flex-col gap-2">
            {recent.map((a) => (
              <li key={a.id}>
                <Link
                  to={`/front-desk/appointments/${a.id}`}
                  className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                >
                  <span className="font-mono text-sm text-ink">{a.appointmentRef}</span>
                  <span className="text-xs capitalize text-ink-muted">{a.channel.replace(/_/g, ' ')}</span>
                  <StatusPill status={a.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
