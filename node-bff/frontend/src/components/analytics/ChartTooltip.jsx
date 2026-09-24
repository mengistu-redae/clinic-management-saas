/**
 * Shared Recharts tooltip content, styled with this app's own card/text
 * tokens instead of Recharts' default browser-chrome tooltip - so it stays
 * correct in both themes automatically (no separate dark-mode tooltip
 * styling needed, the tokens already flip).
 */
export default function ChartTooltip({ active, payload, label, formatLabel, formatValue }) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <div className="rounded-lg border border-slate-200 bg-surface px-3 py-2 text-xs shadow-md">
      <p className="mb-1 font-semibold text-ink">{formatLabel ? formatLabel(label) : label}</p>
      {payload.map((entry, i) => (
        <p key={i} className="flex items-center gap-2 text-ink-muted">
          <span className="inline-block h-2 w-2 rounded-full" style={{ backgroundColor: entry.color || entry.payload?.fill }} />
          <span className="font-mono text-ink">{formatValue ? formatValue(entry.value, entry) : entry.value}</span>
        </p>
      ))}
    </div>
  );
}
