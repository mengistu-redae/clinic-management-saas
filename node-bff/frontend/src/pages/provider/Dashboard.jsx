import { Link } from 'react-router-dom';
import { useMySchedule } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';

/** Provider landing page - see GET /api/my-schedule (already day-scoped server-side, ZoneOffset.UTC). */
export default function ProviderDashboard() {
  const { data, isLoading, isError, error, refetch } = useMySchedule(true);

  if (isError) {
    return <ErrorBanner message={error?.message} onRetry={refetch} />;
  }
  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const seenCount = data.filter((a) => ['with_provider', 'checked_out'].includes(a.status)).length;

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-bold text-ink">Today's Schedule</h1>

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard label="Patients today" value={data.length} />
        <StatCard label="Seen so far" value={seenCount} hint={`${data.length - seenCount} remaining`} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
        <p className="mb-3 text-sm font-semibold text-ink">Today's appointments</p>
        {data.length === 0 ? (
          <EmptyState title="Nothing scheduled today" description="Appointments booked with you for today will show up here." />
        ) : (
          <ul className="flex flex-col gap-2">
            {data.map((a) => (
              <li key={a.id}>
                <Link
                  to={`/provider/appointments/${a.id}/encounter`}
                  className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                >
                  <span className="font-mono text-sm text-ink">{a.appointmentRef}</span>
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
