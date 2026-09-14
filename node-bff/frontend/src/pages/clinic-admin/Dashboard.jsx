import { Link } from 'react-router-dom';
import {
  useProviders,
  useRooms,
  useAppointmentTypes,
  useLabOrderRequests,
  useAppointments,
} from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';

const ACTIVE_APPOINTMENT_STATUSES = new Set(['booked', 'checked_in', 'roomed', 'with_provider']);

/**
 * Clinic-admin landing page - summary counts composed from existing list
 * endpoints (providers/rooms/appointment-types already support
 * ?status=active, phase 5). No settings/CRUD screens yet - see the frontend
 * plan; this page is purely a read-only overview.
 */
export default function ClinicAdminDashboard() {
  const providers = useProviders(true, 'active');
  const rooms = useRooms(true, 'active');
  const appointmentTypes = useAppointmentTypes(true, 'active');
  const labOrderRequests = useLabOrderRequests(true);
  const appointments = useAppointments(true);

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

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">Clinic Overview</h1>
        <Link to="/clinic-admin/settings" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark">
          Manage settings
        </Link>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <Link to="/clinic-admin/providers" className="block transition-shadow hover:shadow-md">
          <StatCard label="Active providers" value={providers.data.length} />
        </Link>
        <Link to="/clinic-admin/rooms" className="block transition-shadow hover:shadow-md">
          <StatCard label="Active rooms" value={rooms.data.length} />
        </Link>
        <Link to="/clinic-admin/appointment-types" className="block transition-shadow hover:shadow-md">
          <StatCard label="Appointment types" value={appointmentTypes.data.length} />
        </Link>
        <StatCard label="Active appointments" value={activeAppointmentCount} />
        <Link to="/lab-orders" className="block transition-shadow hover:shadow-md">
          <StatCard label="Pending lab requests" value={labOrderRequests.data.length} hint="awaiting confirm & order" />
        </Link>
      </div>
    </div>
  );
}
