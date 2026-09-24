import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import am from './locales/am.json';

/**
 * English + Amharic (frontend phase N) - the two languages pinned when
 * this was scoped. `initReactI18next` registers this instance globally, so
 * `useTranslation()` works anywhere without an explicit <I18nextProvider>
 * wrapper - theme/LanguageProvider.jsx is the one place that actually
 * calls `i18n.changeLanguage(...)`, driven by the same localStorage-only
 * persistence model theme/ThemeProvider.jsx already established.
 *
 * Scope boundary, decided when this was planned: only frontend-authored
 * copy lives here. Error text returned from spring-boot-api (validation
 * messages, ResponseStatusException reasons) is never run through this -
 * it stays English-only, the same "backend-owned vocabulary" treatment
 * this app already gives things like ICD-10 codes.
 */
i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    am: { translation: am },
  },
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export default i18n;
