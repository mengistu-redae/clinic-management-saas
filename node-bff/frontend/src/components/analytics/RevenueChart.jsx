import { useTranslation } from 'react-i18next';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { useChartPalette } from './chartPalette.js';
import ChartTooltip from './ChartTooltip.jsx';
import { formatDayLabel, formatCurrency } from '../../lib/format.js';

/** Payments collected per day - same single-hue magnitude-over-time shape as AppointmentVolumeChart, teal instead of primary so the two trends are visually distinct at a glance. */
export default function RevenueChart({ data }) {
  const { t } = useTranslation();
  const palette = useChartPalette();

  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-muted">{t('clinicAnalytics.noDataYet')}</p>;
  }

  const tickInterval = Math.max(0, Math.ceil(data.length / 7) - 1);

  return (
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
        <YAxis tickFormatter={(v) => formatCurrency(v)} tick={{ fill: palette.axis, fontSize: 11 }} axisLine={false} tickLine={false} width={56} />
        <Tooltip content={<ChartTooltip formatLabel={formatDayLabel} formatValue={(v) => formatCurrency(v)} />} />
        <Area
          type="monotone"
          dataKey="total"
          stroke={palette.teal}
          strokeWidth={2}
          fill={palette.teal}
          fillOpacity={0.1}
          dot={{ r: 3, fill: palette.teal, strokeWidth: 0 }}
          activeDot={{ r: 5, strokeWidth: 2, stroke: palette.surface }}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}
