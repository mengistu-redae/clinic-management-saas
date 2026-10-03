/**
 * In-page client-side tabs - the same visual language
 * `clinic-admin/SettingsLayout.jsx` already established (an underlined
 * active tab, muted inactive ones), but driven by local state instead of
 * nested routes. Deliberately not route-based: these split up one
 * already-loaded resource's own long detail page (front-desk/
 * AppointmentDetail.jsx, provider/Encounter.jsx, lab-orders/LabOrderDetail.jsx,
 * accountant/Budgets.jsx) into sections, and every query/mutation for
 * every section is already fetched unconditionally at the top of those
 * components - switching tabs must never re-fetch or lose in-progress
 * form state, which a route change would risk.
 *
 * `tabs` is `[{ key, label }]`; the caller renders each section's content
 * itself, gated on `active === key` - this component is just the tab bar.
 */
export default function Tabs({ tabs, active, onChange }) {
  return (
    <nav className="mb-5 flex gap-6 overflow-x-auto border-b border-slate-200" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          role="tab"
          aria-selected={active === tab.key}
          onClick={() => onChange(tab.key)}
          className={`shrink-0 border-b-2 px-1 pb-2 text-sm font-medium transition-colors ${
            active === tab.key ? 'border-brand text-brand-text' : 'border-transparent text-ink-muted hover:text-ink'
          }`}
        >
          {tab.label}
        </button>
      ))}
    </nav>
  );
}
