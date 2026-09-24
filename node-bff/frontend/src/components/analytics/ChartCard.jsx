/** Shared card shell for one analytics panel - same border/surface/shadow as StatCard.jsx. */
export default function ChartCard({ title, subtitle, action, children }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold text-ink">{title}</p>
          {subtitle && <p className="text-xs text-ink-muted">{subtitle}</p>}
        </div>
        {action}
      </div>
      {children}
    </div>
  );
}
