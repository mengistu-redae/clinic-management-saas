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
      {/* A fixed floor, not just whatever each chart's own ResponsiveContainer
          height happens to be - StatusBreakdownChart renders a ~24px legend
          row above its own 220px chart (244px total), while the other three
          panels are a flat 220-240px with nothing else. Without this, the
          2x2 analytics grid's second row (Grid auto-stretches to its
          tallest card) reads visibly taller than its first row, and the
          shorter card in that row is left with dead space - a real,
          measurable mismatch found in review, not eyeballed. 250px clears
          every current panel's own tallest combination with a few px to
          spare; extra space in a shorter chart just sits blank below it,
          same as normal block flow. */}
      <div className="min-h-[250px]">{children}</div>
    </div>
  );
}
