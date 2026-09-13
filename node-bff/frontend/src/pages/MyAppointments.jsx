import { Link } from 'react-router-dom';
import { useMyAppointments } from '../api/queries.js';
import StatusPill from '../components/StatusPill.jsx';
import EmptyState from '../components/EmptyState.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import Skeleton from '../components/Skeleton.jsx';
import { formatDateTime } from '../lib/format.js';

/** The patient dashboard shows a top-5 preview; this is the full list. */
export default function MyAppointments() {
  const { data, isLoading, isError, error, refetch } = useMyAppointments(true);

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">My Appointments</h1>
        <Link to="/book" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          Book new
        </Link>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      )}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {data && data.length === 0 && (
        <EmptyState title="No appointments yet" description="Book an appointment and it will show up here." />
      )}
      {data && data.length > 0 && (
        <div className="flex flex-col gap-2">
          {data.map((a) => (
            <Link
              key={a.id}
              to={`/appointments/${a.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <p className="font-mono text-xs text-ink-muted">{a.appointmentRef}</p>
                <p className="text-sm text-ink">{formatDateTime(a.startTime)}</p>
              </div>
              <StatusPill status={a.status} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
