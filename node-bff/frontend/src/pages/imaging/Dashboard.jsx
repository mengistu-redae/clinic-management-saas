import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useImagingOrders } from '../../api/queries.js';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import Card from '../../components/Card.jsx';

const NEEDS_IMAGING_ACTION = new Set(['ordered', 'scheduled', 'in_progress']);

/**
 * The imaging_technologist's own landing page - mirrors pages/lab/Dashboard.jsx's
 * minimal shape exactly (lab module L8), minus the QC-log stat cards (no
 * equivalent for imaging). No dedicated worklist endpoint - "needs action"
 * is computed client-side against the same plain GET /api/imaging-orders
 * list ImagingOrders.jsx already fetches.
 */
export default function ImagingDashboard() {
  const { t } = useTranslation();
  const ordersQuery = useImagingOrders(true);

  if (ordersQuery.isError) {
    return (
      <PageContainer width="lg">
        <ErrorBanner message={ordersQuery.error?.message} onRetry={ordersQuery.refetch} />
      </PageContainer>
    );
  }
  if (ordersQuery.isLoading) {
    return (
      <PageContainer width="lg">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 2 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      </PageContainer>
    );
  }

  const orders = ordersQuery.data || [];
  const needsAction = orders.filter((o) => NEEDS_IMAGING_ACTION.has(o.status)).length;

  return (
    <PageContainer width="lg">
      <PageHeader title={t('nav.dashboard')} description={t('imagingDashboardPage.subtitle')} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <StatCard label={t('imagingDashboardPage.needsAction')} value={needsAction} mono />
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Card as={Link} to="/imaging-orders" hover className="text-sm font-semibold text-ink">
          {t('nav.imaging.imagingOrders')}
        </Card>
      </div>
    </PageContainer>
  );
}
