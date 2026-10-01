/**
 * The pill-tab-group pattern this pharmacist module had copy-pasted three
 * times (Dashboard.jsx's day-range window toggle, ControlledSubstanceQueue.jsx/
 * RefillRequests.jsx's status-filter tabs) with drifting accessibility
 * completeness - only Dashboard.jsx's own copy ever got an `aria-label` on
 * the `role="group"` wrapper. One shared copy so the label (now required)
 * can't silently go missing on a future fourth instance.
 *
 * `options`: [{ value, label }]. `compact` matches Dashboard.jsx's
 * slightly tighter original padding (px-2.5 py-1 vs. px-3 py-1.5) so
 * swapping this in doesn't visually resize either existing shape.
 */
export default function TabGroup({ options, value, onChange, ariaLabel, compact = false }) {
  return (
    <div
      className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium"
      role="group"
      aria-label={ariaLabel}
    >
      {options.map((opt) => (
        <button
          key={opt.value || 'all'}
          type="button"
          onClick={() => onChange(opt.value)}
          aria-pressed={value === opt.value}
          className={`rounded-md transition-colors ${compact ? 'px-2.5 py-1' : 'px-3 py-1.5'} ${
            value === opt.value ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
