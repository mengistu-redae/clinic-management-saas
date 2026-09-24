import i18n from '../i18n/index.js';

/**
 * Locale-aware date/time/number formatting (frontend phase N) - reads the
 * active UI language (en/am) from the shared i18next instance rather than
 * passing `undefined` (which defers to the browser's own locale regardless
 * of which language the user picked in the UI). Formatters are built fresh
 * on every call instead of once at module load, since `i18n.language` can
 * change at runtime after a LanguageToggle switch. `Intl` needs no extra
 * data for `'am'` in evergreen browsers (full ICU is bundled), so this
 * needed no new dependency.
 */

function dateTimeFormatter() {
  return new Intl.DateTimeFormat(i18n.language, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

function timeFormatter() {
  return new Intl.DateTimeFormat(i18n.language, { hour: 'numeric', minute: '2-digit' });
}

function dayLabelFormatter() {
  return new Intl.DateTimeFormat(i18n.language, { weekday: 'short', month: 'short', day: 'numeric' });
}

export function formatDateTime(iso) {
  if (!iso) return '—';
  return dateTimeFormatter().format(new Date(iso));
}

export function formatTime(iso) {
  if (!iso) return '—';
  return timeFormatter().format(new Date(iso));
}

export function formatCurrency(amount) {
  if (amount === null || amount === undefined) return '—';
  return new Intl.NumberFormat(i18n.language, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

/** "Tue, Sep 2" - short day label, used to group open slots by day. */
export function formatDayLabel(iso) {
  return dayLabelFormatter().format(new Date(iso));
}
