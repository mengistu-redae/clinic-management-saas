import { useTranslation } from 'react-i18next';
import { BarChart, Bar, Cell, XAxis, YAxis, CartesianGrid, Tooltip, LabelList, ResponsiveContainer } from 'recharts';
import { useChartPalette } from './chartPalette.js';
import ChartTooltip from './ChartTooltip.jsx';

const STATUS_ORDER = ['booked', 'checked_in', 'roomed', 'with_provider', 'checked_out', 'no_show', 'cancelled'];
const LOST_STATUSES = new Set(['no_show', 'cancelled']);

/**
 * Current-snapshot count of every appointment this tenant has, by status -
 * a status-outcome job, not plain categorical identity, so it gets exactly
 * two colors (validated for CVD separation, see chartPalette.js) rather
 * than one hue per status: "lost" (no_show/cancelled) vs everything still
 * in progress or completed. A legend names both colors since 2+ series are
 * on screen at once; each bar is also independently labeled by its own
 * status name on the axis, so identity never depends on the color alone.
 */
export default function StatusBreakdownChart({ data }) {
  const { t } = useTranslation();
  const palette = useChartPalette();

  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-muted">{t('clinicAnalytics.noDataYet')}</p>;
  }

  const byStatus = new Map(data.map((d) => [d.status, Number(d.total)]));
  const seen = new Set();
  const rows = [];
  for (const status of STATUS_ORDER) {
    if (byStatus.has(status)) {
      rows.push({ status, total: byStatus.get(status) });
      seen.add(status);
    }
  }
  // Any status this app adds later that isn't in STATUS_ORDER yet still shows up, just at the end.
  data.forEach((d) => {
    if (!seen.has(d.status)) rows.push({ status: d.status, total: Number(d.total) });
  });

  const statusLabel = (s) => t(`status.${s}`, { defaultValue: s });
  const srList = rows.map((r) => `${statusLabel(r.status)}: ${r.total}`).join(', ');

  return (
    <>
      <p className="sr-only">{t('clinicAnalytics.srSummaryStatusBreakdown', { list: srList })}</p>
      <div className="mb-2 flex flex-wrap items-center gap-4 text-xs text-ink-muted">
        <LegendSwatch color={palette.primary} label={t('clinicAnalytics.legendInProgress')} />
        <LegendSwatch color={palette.danger} label={t('clinicAnalytics.legendLost')} />
      </div>
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={rows} layout="vertical" margin={{ top: 4, right: 32, left: 8, bottom: 4 }}>
          <CartesianGrid stroke={palette.grid} horizontal={false} />
          <XAxis type="number" allowDecimals={false} tick={{ fill: palette.axis, fontSize: 11 }} axisLine={false} tickLine={false} />
          <YAxis
            type="category"
            dataKey="status"
            tickFormatter={statusLabel}
            tick={{ fill: palette.axis, fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            width={110}
          />
          <Tooltip content={<ChartTooltip formatLabel={statusLabel} formatValue={(v) => v} />} />
          <Bar dataKey="total" radius={[0, 4, 4, 0]} maxBarSize={20}>
            {rows.map((row) => (
              <Cell key={row.status} fill={LOST_STATUSES.has(row.status) ? palette.danger : palette.primary} />
            ))}
            <LabelList dataKey="total" position="right" fill={palette.ink} fontSize={11} />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </>
  );
}

function LegendSwatch({ color, label }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className="inline-block h-2.5 w-2.5 rounded-full" style={{ backgroundColor: color }} />
      {label}
    </span>
  );
}
