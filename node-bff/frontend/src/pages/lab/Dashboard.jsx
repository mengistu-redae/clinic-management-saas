import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useLabOrders, useQcRuns } from '../../api/queries.js';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Card from '../../components/Card.jsx';

const NEEDS_LAB_ACTION = new Set(['ordered', 'specimen_collected', 'in_transit']);

/**
 * The lab_technician's own landing page (lab module L8) - no dedicated
 * backend worklist endpoint exists (unlike pharmacy's own /api/pharmacy/queue),
 * so "needs lab action" is computed client-side against the same plain
 * GET /api/lab-orders list LabOrders.jsx already fetches, same reasoning
 * front-desk/Appointments.jsx already filters client-side rather than
 * adding a new server-side filter for a list this small.
 */
export default function LabDashboard() {
  const { t } = useTranslation();
  const ordersQuery = useLabOrders(true);
  const qcRunsQuery = useQcRuns(true);

  const queries = [ordersQuery, qcRunsQuery];
  const isLoading = queries.some((q) => q.isLoading);
  const isError = queries.some((q) => q.isError);

  if (isError) {
    return (
      <PageContainer width="lg">
        <ErrorBanner message={queries.find((q) => q.isError)?.error?.message} onRetry={() => queries.forEach((q) => q.refetch())} />
      </PageContainer>
    );
  }
  if (isLoading) {
    return (
      <PageContainer width="lg">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      </PageContainer>
    );
  }

  const orders = ordersQuery.data || [];
  const needsAction = orders.filter((o) => NEEDS_LAB_ACTION.has(o.status)).length;
  const qcRuns = qcRunsQuery.data || [];
  const today = new Date().toISOString().slice(0, 10);
  const qcRunsToday = qcRuns.filter((r) => (r.performedAt || '').slice(0, 10) === today).length;
  const failedQcToday = qcRuns.filter((r) => (r.performedAt || '').slice(0, 10) === today && r.pass === false).length;

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.dashboard')} description={t('labDashboardPage.subtitle')} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <StatCard label={t('labDashboardPage.needsAction')} value={needsAction} mono />
        <StatCard label={t('labDashboardPage.qcRunsToday')} value={qcRunsToday} mono />
        <StatCard label={t('labDashboardPage.failedQcToday')} value={failedQcToday} mono />
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card as={Link} to="/lab-orders" hover className="text-sm font-semibold text-ink">
          {t('nav.lab.labOrders')}
        </Card>
        <Card as={Link} to="/lab/qc-runs" hover className="text-sm font-semibold text-ink">
          {t('nav.lab.qcLog')}
        </Card>
      </div>
    </PageContainer>
  );
}
