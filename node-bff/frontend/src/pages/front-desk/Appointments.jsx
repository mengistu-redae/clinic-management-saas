import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useAppointments, usePatients } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import { formatDateTime } from '../../lib/format.js';

/**
 * Tenant-wide appointment list (front_desk/clinic_admin/provider) - see
 * GET /api/appointments. Appointment carries only a patientId (or, for a
 * guest booking, a contactName) - patient names are resolved via one
 * GET /api/patients call and an id->name map, rather than an N+1 lookup
 * per row.
 */
export default function Appointments() {
  const appointments = useAppointments(true);
  const patients = usePatients();

  const patientNames = useMemo(() => {
    const map = new Map();
    (patients.data || []).forEach((p) => map.set(p.id, `${p.firstName} ${p.lastName}`));
    return map;
  }, [patients.data]);

  const isLoading = appointments.isLoading || patients.isLoading;
  const isError = appointments.isError || patients.isError;

  if (isError) {
    return (
      <ErrorBanner
        message={appointments.error?.message || patients.error?.message}
        onRetry={() => {
          appointments.refetch();
          patients.refetch();
        }}
      />
    );
  }
  if (isLoading) {
    return (
      <div className="flex flex-col gap-2">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    );
  }

  const rows = [...appointments.data].sort((a, b) => new Date(b.bookedAt) - new Date(a.bookedAt));

  return (
    <div className="mx-auto max-w-2xl">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Appointments</h1>
        <Link to="/front-desk/patients" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          Book for a walk-in
        </Link>
      </div>

      {rows.length === 0 ? (
        <EmptyState title="No appointments yet" description="Bookings for this clinic will show up here." />
      ) : (
        <div className="flex flex-col gap-2">
          {rows.map((a) => (
            <Link
              key={a.id}
              to={`/front-desk/appointments/${a.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <p className="text-sm font-medium text-ink">
                  {(a.patientId && patientNames.get(a.patientId)) || a.contactName || 'Guest'}
                </p>
                <p className="font-mono text-xs text-ink-muted">
                  {a.appointmentRef} · {formatDateTime(a.bookedAt)}
                </p>
              </div>
              <StatusPill status={a.status} />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
