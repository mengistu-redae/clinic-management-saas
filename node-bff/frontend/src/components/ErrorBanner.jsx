import { useTranslation } from 'react-i18next';

/**
 * `message` itself is deliberately NOT translated - it's usually either a
 * frontend-authored string from the specific page that renders this (not
 * yet converted to a translation key, same "wide sweep still pending" scope
 * boundary as everywhere else in frontend phase N) or, just as often,
 * backend error text passed straight through - which stays English-only by
 * design, see i18n/index.js's own scope-boundary comment. Only this
 * component's own two static strings (the fallback and the retry button)
 * are translated here.
 */
export default function ErrorBanner({ message, onRetry }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center justify-between gap-4 rounded-xl border border-danger/30 bg-danger-light px-4 py-3">
      <p className="text-sm text-danger">{message || t('common.somethingWrong')}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded-lg border border-danger/40 px-3 py-1.5 text-sm font-medium text-danger hover:bg-danger/10"
        >
          {t('common.tryAgain')}
        </button>
      )}
    </div>
  );
}
