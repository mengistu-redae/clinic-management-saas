import { useEffect, useRef, useState } from 'react';

/**
 * Generic icon-triggered dropdown - the click-outside + Escape mechanism
 * PreferencesMenu.jsx originally used for one combined "Preferences"
 * button, pulled out into a reusable primitive now that Language and
 * Theme each get their own small icon trigger instead (a further UI
 * revision - two icons read more clearly than one text button, per
 * direct request). No dropdown/popover library exists anywhere in this
 * app; this is the entire mechanism, matching the "reach for plain
 * hooks, not a new dependency" convention already used everywhere else.
 *
 * `icon` is a already-sized JSX element (e.g. `<LanguageIcon
 * className="h-5 w-5" />`) - this component doesn't know or care what it
 * is. `label` is the trigger's accessible name (icon-only buttons carry
 * no visible text, so this is required, not decorative) and doubles as
 * its native tooltip via `title`.
 */
export default function IconMenu({ icon, label, children }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    function handlePointerDown(event) {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false);
      }
    }
    function handleKeyDown(event) {
      if (event.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={label}
        title={label}
        className="rounded-lg border border-slate-200 p-2 text-ink-muted hover:bg-slate-100 hover:text-ink"
      >
        {icon}
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-56 max-w-[calc(100vw-2rem)] rounded-xl border border-slate-200 bg-surface p-3 shadow-lg">
          {children}
        </div>
      )}
    </div>
  );
}
