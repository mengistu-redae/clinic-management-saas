import { useMyAppointments, useMyLabOrders } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';

const ACTIVE_APPOINTMENT_STATUSES = new Set(['booked', 'checked_in', 'roomed', 'with_provider']);
const OPEN_LAB_ORDER_STATUSES = new Set(['requested', 'ordered', 'specimen_collected', 'in_transit', 'resulted']);

function ComingSoonButton({ children }) {
  return (
    <span
      title="Coming in a later phase"
      className="cursor-not-allowed rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink-muted opacity-60"
    >
      {children}
    </span>
  );
}

function ListPanel({ title, action, children }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <div className="mb-3 flex items-center justify-between">
        <p className="text-sm font-semibold text-ink">{title}</p>
        {action}
      </div>
      {children}
    </div>
  );
}

/** Patient landing page - see GET /api/my-appointments, GET /api/my-lab-orders. */
export default function PatientDashboard() {
  const appointments = useMyAppointments(true);
  const labOrders = useMyLabOrders(true);

  const isLoading = appointments.isLoading || labOrders.isLoading;
  const isError = appointments.isError || labOrders.isError;

  if (isError) {
    return (
      <ErrorBanner
        message={appointments.error?.message || labOrders.error?.message}
        onRetry={() => {
          appointments.refetch();
          labOrders.refetch();
        }}
      />
    );
  }

  if (isLoading) {
    return (
      <div className="grid gap-4 sm:grid-cols-2">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  const myAppointments = [...appointments.data].sort(
    (a, b) => new Date(b.bookedAt) - new Date(a.bookedAt),
  );
  const activeAppointments = myAppointments.filter((a) => ACTIVE_APPOINTMENT_STATUSES.has(a.status));

  const myLabOrders = [...labOrders.data].sort(
    (a, b) => new Date(b.order.orderedAt) - new Date(a.order.orderedAt),
  );
  const openLabOrders = myLabOrders.filter((o) => OPEN_LAB_ORDER_STATUSES.has(o.order.status));

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">My Dashboard</h1>
        <div className="flex gap-2">
          <ComingSoonButton>Book an appointment</ComingSoonButton>
          <ComingSoonButton>Request a lab test</ComingSoonButton>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard label="Active appointments" value={activeAppointments.length} hint={`${myAppointments.length} total`} />
        <StatCard label="Open lab orders" value={openLabOrders.length} hint={`${myLabOrders.length} total`} />
      </div>

      <div className="grid gap-8 lg:grid-cols-2">
        <ListPanel title="My appointments">
          {myAppointments.length === 0 ? (
            <EmptyState title="No appointments yet" description="Appointments you book will show up here." />
          ) : (
            <ul className="flex flex-col gap-2">
              {myAppointments.slice(0, 5).map((a) => (
                <li key={a.id} className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2">
                  <span className="font-mono text-sm text-ink">{a.appointmentRef}</span>
                  <StatusPill status={a.status} />
                </li>
              ))}
            </ul>
          )}
        </ListPanel>

        <ListPanel title="My lab orders">
          {myLabOrders.length === 0 ? (
            <EmptyState title="No lab orders yet" description="Lab orders and requests you've made will show up here." />
          ) : (
            <ul className="flex flex-col gap-2">
              {myLabOrders.slice(0, 5).map(({ order }) => (
                <li key={order.id} className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2">
                  <span className="font-mono text-sm text-ink">{order.orderRef}</span>
                  <StatusPill status={order.status} />
                </li>
              ))}
            </ul>
          )}
        </ListPanel>
      </div>
    </div>
  );
}
