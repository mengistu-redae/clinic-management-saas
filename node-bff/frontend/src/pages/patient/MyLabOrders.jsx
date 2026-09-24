import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyLabOrders } from '../../api/queries.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatDateTime } from '../../lib/format.js';

/** The full list the dashboard's own top-5 preview links out to - same relationship as MyAppointments.jsx. GET /api/my-lab-orders. */
export default function MyLabOrders() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useMyLabOrders(true);

  const orders = [...(data || [])].sort((a, b) => new Date(b.order.orderedAt) - new Date(a.order.orderedAt));

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">{t('myLabOrders.title')}</h1>
        <Link to="/my-lab-orders/request" className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark">
          {t('myLabOrders.requestTest')}
        </Link>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-3">
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      )}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && orders.length === 0 && (
        <EmptyState title={t('myLabOrders.emptyTitle')} description={t('myLabOrders.emptyDescription')} />
      )}

      {!isLoading && !isError && orders.length > 0 && (
        <div className="flex flex-col gap-3">
          {orders.map(({ order, tests }) => (
            <Link
              key={order.id}
              to={`/my-lab-orders/${order.id}`}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <div className="mb-1 flex items-center gap-2">
                  <StatusPill status={order.status} />
                  <span className="font-mono text-xs text-ink-muted">{order.orderRef}</span>
                </div>
                <p className="text-sm text-ink">{t('myLabOrders.testCount', { count: tests.length })}</p>
                <p className="text-xs text-ink-muted">{formatDateTime(order.orderedAt)}</p>
              </div>
              <span className="text-ink-muted">&rsaquo;</span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
