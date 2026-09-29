import { useTranslation } from 'react-i18next';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useChartPalette } from './chartPalette.js';
import ChartTooltip from './ChartTooltip.jsx';

/**
 * Total units dispensed per medication over the analytics window - same
 * categorical bar-top-N shape as ProviderUtilizationChart.jsx, but the
 * backend already embeds `medicationName` directly (see
 * MedicationDispenseCount's own javadoc), so unlike that chart this one
 * needs no separate name-resolution map prop. Capped to the top 8
 * medications for the same readability reason.
 */
export default function MedicationDispenseCountChart({ data }) {
  const { t } = useTranslation();
  const palette = useChartPalette();

  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-muted">{t('clinicAnalytics.noDataYet')}</p>;
  }

  const rows = [...data]
    .map((d) => ({ medicationId: d.medicationId, name: d.medicationName, total: Number(d.total) }))
    .sort((a, b) => b.total - a.total)
    .slice(0, 8);

  return (
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
  );
}
