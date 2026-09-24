import { useTranslation } from 'react-i18next';
import { useLanguage } from '../theme/LanguageProvider.jsx';

/**
 * English/Amharic switcher (frontend phase N) - same plain segmented
 * -control shape as ThemeToggle.jsx, dropped next to it in both
 * AppShell.jsx and PublicShell.jsx headers. Labels are each language's own
 * name in its own script (matches how most apps present a language
 * switcher - "English" isn't translated to Amharic here, and "አማርኛ" isn't
 * transliterated to English), not `t('language.en'/'language.am')`, so the
 * choice stays legible to someone who can't yet read the currently-active
 * language.
 */
export default function LanguageToggle() {
  const { t } = useTranslation();
  const { language, setLanguage } = useLanguage();

  return (
    <div className="inline-flex rounded-lg border border-slate-200 p-0.5 text-xs font-medium" role="group" aria-label={t('language.label')}>
      <button
        type="button"
        onClick={() => setLanguage('en')}
        aria-pressed={language === 'en'}
        className={`rounded-md px-2.5 py-1 transition-colors ${
          language === 'en' ? 'bg-brand-light text-brand' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
        }`}
      >
        English
      </button>
      <button
        type="button"
        onClick={() => setLanguage('am')}
        aria-pressed={language === 'am'}
        lang="am"
        className={`rounded-md px-2.5 py-1 transition-colors ${
          language === 'am' ? 'bg-brand-light text-brand' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
        }`}
      >
        አማርኛ
      </button>
    </div>
  );
}
