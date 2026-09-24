import { useTranslation } from 'react-i18next';
import { useTheme } from '../theme/ThemeProvider.jsx';

/**
 * Compact three-way Light/Dark/System control (frontend phase N) - a plain
 * segmented button group, matching this app's existing text-only UI (no
 * icon library anywhere else in the codebase). Dropped into both
 * AppShell.jsx (staff) and PublicShell.jsx (public) headers - the theme
 * preference applies everywhere, not just to signed-in staff.
 */
export default function ThemeToggle() {
  const { t } = useTranslation();
  const { theme, setTheme } = useTheme();
  const options = [
    { value: 'light', label: t('theme.light') },
    { value: 'dark', label: t('theme.dark') },
    { value: 'system', label: t('theme.system') },
  ];

  return (
    <div className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group" aria-label={t('theme.label')}>
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => setTheme(opt.value)}
          aria-pressed={theme === opt.value}
          className={`rounded-md px-2.5 py-1 transition-colors ${
            theme === opt.value ? 'bg-brand-light text-brand' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
