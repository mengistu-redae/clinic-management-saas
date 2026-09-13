import { usePlatformClinics } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';

/** Platform-admin landing page - see GET /api/platform/clinics (every clinic, any status). */
export default function PlatformAdminDashboard() {
  const { data, isLoading, isError, error, refetch } = usePlatformClinics(true);

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

  const activeCount = data.filter((c) => c.status === 'active').length;

  return (
    <div className="flex flex-col gap-8">
      <h1 className="text-2xl font-bold text-ink">Platform Overview</h1>

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard label="Total clinics" value={data.length} />
        <StatCard label="Active" value={activeCount} hint={`${data.length - activeCount} deactivated`} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
        <p className="mb-3 text-sm font-semibold text-ink">Clinics</p>
        {data.length === 0 ? (
          <EmptyState title="No clinics yet" description="Clinics onboarded onto the platform will show up here." />
        ) : (
          <ul className="flex flex-col gap-2">
            {data.map((c) => (
              <li key={c.id} className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2">
                <span className="text-sm text-ink">{c.name}</span>
                <StatusPill status={c.status} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
