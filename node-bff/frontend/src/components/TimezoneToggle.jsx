import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useTimezone } from '../theme/TimezoneProvider.jsx';
import { supportedTimezones } from '../lib/timezone.js';

/**
 * Browser-local / clinic's-own / manually-picked timezone display control
 * (frontend phase N) - same plain segmented-button shape as
 * ThemeToggle.jsx/LanguageToggle.jsx, dropped next to them in both
 * AppShell.jsx and PublicShell.jsx headers. "Manual" reveals a native
 * <select> of every IANA zone id (Intl.supportedValuesOf('timeZone') - a
 * real standard API, not a bundled ~400-entry list).
 */
export default function TimezoneToggle() {
  const { t } = useTranslation();
  const { mode, manualZone, activeClinicZone, setMode, setManualZone } = useTimezone();
  const zones = useMemo(supportedTimezones, []);

  const options = [
    { value: 'browser', label: t('timezone.browser') },
    { value: 'clinic', label: t('timezone.clinic') },
    { value: 'manual', label: t('timezone.manual') },
  ];

  return (
    <div className="flex items-center gap-2">
      <div className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group" aria-label={t('timezone.label')}>
        {options.map((opt) => (
          <button
            key={opt.value}
            type="button"
            onClick={() => setMode(opt.value)}
            aria-pressed={mode === opt.value}
            className={`rounded-md px-2.5 py-1 transition-colors ${
              mode === opt.value ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>
      {mode === 'manual' && zones.length > 0 && (
        <select
          value={manualZone || ''}
          onChange={(e) => setManualZone(e.target.value || null)}
          aria-label={t('timezone.pickZoneLabel')}
          className="rounded-lg border border-slate-200 bg-surface px-2 py-1 text-xs text-ink"
        >
          <option value="" disabled>
            {t('timezone.pickZonePlaceholder')}
          </option>
          {zones.map((zone) => (
            <option key={zone} value={zone}>
              {zone}
            </option>
          ))}
        </select>
      )}
      {mode === 'clinic' && !activeClinicZone && (
        <span className="text-xs text-ink-muted">{t('timezone.clinicUnknownHere')}</span>
      )}
    </div>
  );
}
