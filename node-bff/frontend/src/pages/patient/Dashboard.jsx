import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyAppointments, useMyLabOrders } from '../../api/queries.js';
import StatCard from '../../components/StatCard.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Button from '../../components/Button.jsx';
import { formatDateTime } from '../../lib/format.js';

const ACTIVE_APPOINTMENT_STATUSES = new Set(['booked', 'checked_in', 'roomed', 'with_provider']);
const OPEN_LAB_ORDER_STATUSES = new Set(['requested', 'ordered', 'specimen_collected', 'in_transit', 'resulted']);

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
  const { t } = useTranslation();
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
        <h1 className="text-2xl font-bold text-ink">{t('patientDashboard.title')}</h1>
        <div className="flex gap-2">
          <Button as={Link} to="/book">
            {t('nav.patient.bookAppointment')}
          </Button>
          <Button as={Link} to="/my-lab-orders/request" variant="secondary">
            {t('myLabOrders.requestTest')}
          </Button>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <StatCard
          label={t('patientDashboard.activeAppointments')}
          value={activeAppointments.length}
          hint={t('patientDashboard.totalHint', { count: myAppointments.length })}
        />
        <StatCard
          label={t('patientDashboard.openLabOrders')}
          value={openLabOrders.length}
          hint={t('patientDashboard.totalHint', { count: myLabOrders.length })}
        />
      </div>

      <div className="grid gap-8 lg:grid-cols-2">
        <ListPanel
          title={t('patientDashboard.myAppointmentsPanel')}
          action={
            myAppointments.length > 0 && (
              <Link to="/my-appointments" className="text-xs font-medium text-brand-text hover:underline">
                {t('patientDashboard.viewAll')}
              </Link>
            )
          }
        >
          {myAppointments.length === 0 ? (
            <EmptyState title={t('patientDashboard.emptyTitle')} description={t('patientDashboard.emptyAppointmentsDescription')} />
          ) : (
            <ul className="flex flex-col gap-2">
              {myAppointments.slice(0, 5).map((a) => (
                <li key={a.id}>
                  <Link
                    to={`/appointments/${a.id}`}
                    className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                  >
                    <div>
                      <p className="font-mono text-sm text-ink">{a.appointmentRef}</p>
                      <p className="text-xs text-ink-muted">{formatDateTime(a.startTime)}</p>
                    </div>
                    <StatusPill status={a.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </ListPanel>

        <ListPanel
          title={t('patientDashboard.myLabOrdersPanel')}
          action={
            myLabOrders.length > 0 && (
              <Link to="/my-lab-orders" className="text-xs font-medium text-brand-text hover:underline">
                {t('patientDashboard.viewAll')}
              </Link>
            )
          }
        >
          {myLabOrders.length === 0 ? (
            <EmptyState title={t('myLabOrders.emptyTitle')} description={t('patientDashboard.emptyLabOrdersDescription')} />
          ) : (
            <ul className="flex flex-col gap-2">
              {myLabOrders.slice(0, 5).map(({ order }) => (
                <li key={order.id}>
                  <Link
                    to={`/my-lab-orders/${order.id}`}
                    className="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-2 hover:border-slate-200 hover:bg-slate-50"
                  >
                    <span className="font-mono text-sm text-ink">{order.orderRef}</span>
                    <StatusPill status={order.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </ListPanel>
      </div>
    </div>
  );
}
