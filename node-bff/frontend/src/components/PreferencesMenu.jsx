import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import LanguageToggle from './LanguageToggle.jsx';
import ThemeToggle from './ThemeToggle.jsx';
import TimezoneToggle from './TimezoneToggle.jsx';

/**
 * Consolidates the language/theme/timezone toggles behind one small
 * trigger button - a UI-review finding fixed here: previously three
 * separate boxed button-groups sat directly beside the header's Log
 * in/Log out control in both AppShell.jsx and PublicShell.jsx, visually
 * outweighing it, and (with no responsive collapse strategy of their
 * own) wrapping into several extra header rows at phone width. One
 * trigger collapses to a single compact, secondary-styled element at
 * every width; the panel itself floats above the layout instead of
 * pushing header content down or around.
 *
 * No dropdown/popover library exists anywhere in this app (checked
 * before building this) - a plain click-outside + Escape handler is the
 * entire mechanism, the same "reach for plain hooks, not a new
 * dependency" convention every other stateful component here follows.
 * Renders the three existing toggle components unchanged, just stacked
 * vertically with a label above each instead of side by side - no
 * changes needed to any of them.
 */
export default function PreferencesMenu() {
  const { t } = useTranslation();
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
        className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink"
      >
        {t('preferencesMenu.trigger')}
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-xl border border-slate-200 bg-surface p-4 shadow-lg">
          <div className="flex flex-col gap-4">
            <div>
              <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('language.label')}</p>
              <LanguageToggle />
            </div>
            <div>
              <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('theme.label')}</p>
              <ThemeToggle />
            </div>
            <div>
              <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('timezone.label')}</p>
              <TimezoneToggle />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
