import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useInventoryAnalytics } from '../../api/queries.js';
import PageContainer from '../../components/PageContainer.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatCard from '../../components/StatCard.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ChartCard from '../../components/analytics/ChartCard.jsx';
import ChartTooltip from '../../components/analytics/ChartTooltip.jsx';
import { useChartPalette } from '../../components/analytics/chartPalette.js';
import { formatCurrency } from '../../lib/format.js';

/**
 * The inventory landing page - stat cards + link tiles into the other
 * pages, same shape as accountant/Dashboard.jsx. Consumes the
 * already-built GET /api/inventory/analytics (phase 34), which had no
 * page to live on until now. No day-range toggle - every field here is
 * a current-state snapshot, not a historical series (see
 * InventoryAnalyticsController's own javadoc).
 */
export default function InventoryDashboard() {
  const { t } = useTranslation();
  const analytics = useInventoryAnalytics(true);
  const palette = useChartPalette();

  if (analytics.isError) {
    return (
      <PageContainer width="lg">
        <ErrorBanner message={analytics.error?.message} onRetry={analytics.refetch} />
      </PageContainer>
    );
  }
  if (analytics.isLoading) {
    return (
      <PageContainer width="lg">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      </PageContainer>
    );
  }

  const { totalValuation, lowStock, expiringSoon, assetStatusCounts } = analytics.data;
  const activeAssets = assetStatusCounts.find((s) => s.status === 'in_service')?.total || 0;
  const chartRows = assetStatusCounts.map((s) => ({ status: t(`status.${s.status}`, { defaultValue: s.status }), total: Number(s.total) }));

  return (
    <PageContainer width="lg">
      <PageHeader title={t('inventoryPage.dashboardTitle')} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label={t('inventoryPage.totalValuation')} value={formatCurrency(totalValuation)} mono />
        <StatCard label={t('inventoryPage.lowStockCount')} value={lowStock.length} />
        <StatCard label={t('inventoryPage.expiringSoon')} value={expiringSoon.length} />
        <StatCard label={t('inventoryPage.activeAssets')} value={activeAssets} />
      </div>

      {chartRows.length > 0 && (
        <div className="mt-6">
          <ChartCard title={t('inventoryPage.assetStatusChartTitle')} subtitle={t('inventoryPage.assetStatusChartSubtitle')}>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={chartRows} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid stroke={palette.grid} vertical={false} />
                <XAxis dataKey="status" tick={{ fill: palette.axis, fontSize: 11 }} axisLine={{ stroke: palette.grid }} tickLine={false} />
                <YAxis allowDecimals={false} tick={{ fill: palette.axis, fontSize: 11 }} axisLine={false} tickLine={false} width={28} />
                <Tooltip content={<ChartTooltip formatValue={(v) => v} />} />
                <Bar dataKey="total" fill={palette.primary} radius={[4, 4, 0, 0]} maxBarSize={48} />
              </BarChart>
            </ResponsiveContainer>
          </ChartCard>
        </div>
      )}

      <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Link to="/inventory/items" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.inventory.items')}
        </Link>
        <Link to="/inventory/suppliers" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.inventory.suppliers')}
        </Link>
        <Link to="/inventory/purchase-orders" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.inventory.purchaseOrders')}
        </Link>
        <Link to="/inventory/assets" className="rounded-xl border border-slate-200 bg-surface p-4 text-sm font-semibold text-ink transition-shadow hover:shadow-md">
          {t('nav.inventory.assets')}
        </Link>
      </div>
    </PageContainer>
  );
}
