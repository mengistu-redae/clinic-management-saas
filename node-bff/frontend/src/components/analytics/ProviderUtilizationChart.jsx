import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useChartPalette } from './chartPalette.js';
import ChartTooltip from './ChartTooltip.jsx';

/**
 * Appointment volume per provider over the analytics window - a single
 * series faceted by category (provider), not an identity-color job, so
 * every bar shares one hue; the provider's own name is already the
 * category label doing the identifying. Capped to the busiest 8 providers
 * so the chart stays readable at a clinic with a large roster - the rest
 * still count toward every other panel, just not broken out individually
 * here.
 */
export default function ProviderUtilizationChart({ data, providerNameById }) {
  const { t } = useTranslation();
  const palette = useChartPalette();

  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-muted">{t('clinicAnalytics.noDataYet')}</p>;
  }

  const rows = [...data]
    .map((d) => ({
      providerId: d.providerId,
      name: (d.providerId && providerNameById[d.providerId]) || t('clinicAnalytics.unknownProvider'),
      total: Number(d.total),
    }))
    .sort((a, b) => b.total - a.total)
    .slice(0, 8);

  return (
    <>
      <p className="sr-only">
        {t('clinicAnalytics.srSummaryCategories', { count: rows.length, topLabel: rows[0]?.name, top: rows[0]?.total })}
      </p>
      <ResponsiveContainer width="100%" height={240}>
      <BarChart data={rows} margin={{ top: 8, right: 8, left: 0, bottom: 32 }}>
        <CartesianGrid stroke={palette.grid} vertical={false} />
        <XAxis
          dataKey="name"
          tick={{ fill: palette.axis, fontSize: 11 }}
          axisLine={{ stroke: palette.grid }}
          tickLine={false}
          angle={-25}
          textAnchor="end"
          height={56}
          interval={0}
        />
        <YAxis allowDecimals={false} tick={{ fill: palette.axis, fontSize: 11 }} axisLine={false} tickLine={false} width={28} />
        <Tooltip content={<ChartTooltip formatValue={(v) => v} />} />
        <Bar dataKey="total" fill={palette.primary} radius={[4, 4, 0, 0]} maxBarSize={32} />
      </BarChart>
      </ResponsiveContainer>
    </>
  );
}
