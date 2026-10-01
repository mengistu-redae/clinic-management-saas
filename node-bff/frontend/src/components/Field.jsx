/**
 * The label+input wrapper and input/select/textarea className string this
 * app's forms have each declared locally since phase A - found duplicated
 * verbatim across 13+ page files during the 2026-10-01 UI audit. One shared
 * copy so a future a11y/style fix (e.g. hint `aria-describedby`) lands
 * everywhere at once instead of needing a sweep across every form page.
 */
export const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

export default function Field({ label, hint, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 flex items-baseline gap-2">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
        {hint && <span className="text-xs font-normal normal-case text-ink-muted">({hint})</span>}
      </span>
      {children}
    </label>
  );
}
