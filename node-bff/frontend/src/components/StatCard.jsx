/**
 * One KPI tile on a dashboard: a big number with a label and an optional
 * hint line. Simplified from the reference bus-ticketing-saas project's own
 * StatCard - no delta pill/sparkline, since those need a dedicated backend
 * aggregation endpoint (period-over-period deltas, a 14-day series) this
 * app deliberately doesn't have yet (see the frontend plan's "Deliberate
 * scope boundary" note).
 */
export default function StatCard({ label, value, hint, mono = false }) {
  return (
    <div className="rounded-2xl border border-t-2 border-slate-200 border-t-brand bg-surface p-4 shadow-sm transition-shadow">
      <p className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</p>
      <p className={`mt-1.5 text-3xl font-bold tabular-nums text-ink ${mono ? 'font-mono' : ''}`}>{value}</p>
      {hint && <p className="mt-1 text-xs text-ink-muted">{hint}</p>}
    </div>
  );
}
