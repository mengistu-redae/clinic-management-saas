import { createContext, useContext, useEffect, useState } from 'react';
import i18n from '../i18n/index.js';

const STORAGE_KEY = 'clinicops.language';
const VALID = new Set(['en', 'am']);

const LanguageContext = createContext(null);

function readStoredLanguage() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return VALID.has(stored) ? stored : 'en';
  } catch {
    return 'en';
  }
}

/**
 * English/Amharic UI language (frontend phase N) - localStorage-only, same
 * storage decision and provider shape as theme/ThemeProvider.jsx. Wraps the
 * whole app in main.jsx (outside BrandingProvider, for the same reason
 * ThemeProvider does) so logged-out public pages respect it too.
 *
 * Sets `document.documentElement.lang` on every change - both a real
 * accessibility signal (screen readers switch pronunciation rules) and the
 * hook index.css's `:lang(am)` font-fallback rule keys off (Inter has no
 * Ge'ez-script glyphs; that rule swaps in Noto Sans Ethiopic only for
 * Amharic-rendered text, leaving English content on Inter).
 */
export function LanguageProvider({ children }) {
  const [language, setLanguageState] = useState(readStoredLanguage);

  useEffect(() => {
    i18n.changeLanguage(language);
    document.documentElement.lang = language;
  }, [language]);

  function setLanguage(next) {
    if (!VALID.has(next)) return;
    setLanguageState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // In-memory state still updates - the choice just won't survive a reload.
    }
  }

  return <LanguageContext.Provider value={{ language, setLanguage }}>{children}</LanguageContext.Provider>;
}

/** { language: 'en'|'am', setLanguage } */
export function useLanguage() {
  return useContext(LanguageContext);
}
