import i18n from '../i18n/index.js';
import { resolveTimezone } from './timezone.js';

/**
 * Locale-aware date/time/number formatting (frontend phase N) - reads the
 * active UI language (en/am) from the shared i18next instance rather than
 * passing `undefined` (which defers to the browser's own locale regardless
 * of which language the user picked in the UI). Formatters are built fresh
 * on every call instead of once at module load, since `i18n.language` can
 * change at runtime after a LanguageToggle switch. `Intl` needs no extra
 * data for `'am'` in evergreen browsers (full ICU is bundled), so this
 * needed no new dependency.
 *
 * `formatDateTime`/`formatTime`/`formatDayLabel` take an optional `zone`
 * (a specific record's own clinic timezone, when the caller already knows
 * it) - only actually consulted when the viewer's timezone preference
 * (theme/TimezoneProvider.jsx) is set to "clinic"; resolveTimezone() falls
 * back to the ambient "current clinic" zone, or the browser's own zone,
 * when no explicit `zone` is given. See lib/timezone.js's own comment for
 * the full mode breakdown.
 */

function dateTimeFormatter(zone) {
  return new Intl.DateTimeFormat(i18n.language, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: resolveTimezone(zone),
  });
}

function timeFormatter(zone) {
  return new Intl.DateTimeFormat(i18n.language, { hour: 'numeric', minute: '2-digit', timeZone: resolveTimezone(zone) });
}

function dayLabelFormatter(zone) {
  return new Intl.DateTimeFormat(i18n.language, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    timeZone: resolveTimezone(zone),
  });
}

export function formatDateTime(iso, zone) {
  if (!iso) return '—';
  return dateTimeFormatter(zone).format(new Date(iso));
}

export function formatTime(iso, zone) {
  if (!iso) return '—';
  return timeFormatter(zone).format(new Date(iso));
}

export function formatCurrency(amount) {
  if (amount === null || amount === undefined) return '—';
  return new Intl.NumberFormat(i18n.language, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

/** "Tue, Sep 2" - short day label, used to group open slots by day. */
export function formatDayLabel(iso, zone) {
  return dayLabelFormatter(zone).format(new Date(iso));
}
