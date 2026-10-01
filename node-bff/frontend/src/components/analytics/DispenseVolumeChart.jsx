import { useTranslation } from 'react-i18next';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useChartPalette } from './chartPalette.js';
import ChartTooltip from './ChartTooltip.jsx';
import { formatDayLabel } from '../../lib/format.js';

/**
 * Units dispensed per day, single series - same area-chart-over-time
 * shape as AppointmentVolumeChart.jsx (a magnitude-over-time job, one hue).
 * `total` here is a sum of quantityDispensed, not an event count - "volume"
 * reads more usefully as units dispensed than a row count.
 */
export default function DispenseVolumeChart({ data }) {
  const { t } = useTranslation();
  const palette = useChartPalette();

  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-muted">{t('clinicAnalytics.noDataYet')}</p>;
  }

  const tickInterval = Math.max(0, Math.ceil(data.length / 7) - 1);
  const total = data.reduce((sum, d) => sum + Number(d.total || 0), 0);

  return (
    <>
      <p className="sr-only">{t('clinicAnalytics.srSummaryTimeSeries', { count: data.length, total })}</p>
      <ResponsiveContainer width="100%" height={220}>
      <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid stroke={palette.grid} vertical={false} />
        <XAxis
          dataKey="day"
          tickFormatter={(day) => formatDayLabel(day)}
          interval={tickInterval}
          tick={{ fill: palette.axis, fontSize: 11 }}
          axisLine={{ stroke: palette.grid }}
          tickLine={false}
        />
        <YAxis allowDecimals={false} tick={{ fill: palette.axis, fontSize: 11 }} axisLine={false} tickLine={false} width={28} />
        <Tooltip content={<ChartTooltip formatLabel={formatDayLabel} formatValue={(v) => v} />} />
        <Area
          type="monotone"
          dataKey="total"
          stroke={palette.primary}
          strokeWidth={2}
          fill={palette.primary}
          fillOpacity={0.1}
          dot={{ r: 3, fill: palette.primary, strokeWidth: 0 }}
          activeDot={{ r: 5, strokeWidth: 2, stroke: palette.surface }}
        />
      </AreaChart>
      </ResponsiveContainer>
    </>
  );
}
