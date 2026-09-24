/**
 * Shared timezone-display preference (frontend phase N) - a plain module
 * singleton, not React state, for the same reason i18n/index.js is: this
 * needs to be readable from lib/format.js's formatters, which are called
 * from ordinary functions (list-sorting/grouping code, not just component
 * render), not only from components that can call a hook.
 *
 * Three modes, per the phase-N sketch:
 *  - "browser" (default) - defer entirely to the device's own zone. Passing
 *    `timeZone: undefined` to Intl.DateTimeFormat already does exactly this,
 *    so this mode needs no explicit zone string at all.
 *  - "manual" - always the one IANA zone the viewer picked themselves,
 *    regardless of whatever clinic a given page happens to be showing.
 *  - "clinic" - show a record in *that record's own* clinic's timezone.
 *    This is inherently per-record, not a single global value, so it's
 *    resolved two ways: an ambient `activeClinicZone` (set by
 *    theme/BrandingProvider.jsx for a signed-in staff member, who only ever
 *    has one clinic), or a `recordZone` passed explicitly by a caller that
 *    already knows a specific record's clinic (e.g. the public booking flow,
 *    scoped to one clinicId) - the explicit value always wins when given,
 *    since it's more specific than the ambient one.
 */

// A change here needs every already-rendered date/time on the current page
// to reformat immediately, not just whenever something else happens to
// re-render it. ThemeProvider gets that for free (a CSS class flip
// repaints instantly, no React re-render needed); LanguageProvider gets it
// because every page already calls useTranslation() for its own text, and
// react-i18next unconditionally re-renders on its 'languageChanged' event
// (see its useTranslation() source - an internal revision counter
// increments on every call, regardless of whether the language value
// itself changed). There's no equivalent existing subscription for a
// timezone change, and adding useTimezone() to every one of the ~20 files
// that call formatDateTime/formatTime/formatDayLabel would be exactly the
// kind of repetitive, easy-to-forget wiring this app avoids elsewhere - so
// commit() below re-emits that same event (language unchanged) purely to
// piggyback the identical, already-proven-live mechanism, rather than
// inventing a second one.
import i18n from '../i18n/index.js';

const PREFERENCE_KEY = 'clinicops.timezone';
const VALID_MODES = new Set(['browser', 'clinic', 'manual']);

function readStoredPreference() {
  try {
    const parsed = JSON.parse(localStorage.getItem(PREFERENCE_KEY));
    if (parsed && VALID_MODES.has(parsed.mode)) {
      return { mode: parsed.mode, manualZone: typeof parsed.manualZone === 'string' ? parsed.manualZone : null };
    }
  } catch {
    // Private browsing / storage disabled / bad JSON - fall back below.
  }
  return { mode: 'browser', manualZone: null };
}

let preference = readStoredPreference();
let activeClinicZone = null;
let snapshot = buildSnapshot();
const listeners = new Set();

function buildSnapshot() {
  return { mode: preference.mode, manualZone: preference.manualZone, activeClinicZone };
}

function persist() {
  try {
    localStorage.setItem(PREFERENCE_KEY, JSON.stringify(preference));
  } catch {
    // In-memory state still updates - the choice just won't survive a reload.
  }
}

function commit() {
  snapshot = buildSnapshot();
  listeners.forEach((fn) => fn());
  // See the module comment above - forces every page's already-rendered
  // dates/times to reformat now, not just on the next unrelated re-render.
  i18n.emit('languageChanged', i18n.language);
}

export function getTimezoneSnapshot() {
  return snapshot;
}

export function subscribeTimezone(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function setTimezoneMode(mode) {
  if (!VALID_MODES.has(mode) || mode === preference.mode) return;
  preference = { ...preference, mode };
  persist();
  commit();
}

export function setManualTimezone(zone) {
  const next = zone || null;
  if (next === preference.manualZone) return;
  preference = { ...preference, manualZone: next };
  persist();
  commit();
}

/** Registers (or clears, with a falsy zone) the ambient "current clinic" timezone for "clinic" mode. */
export function setActiveClinicZone(zone) {
  const next = zone || null;
  if (next === activeClinicZone) return;
  activeClinicZone = next;
  commit();
}

/**
 * Resolves the IANA zone id to format a date/time with, or `undefined` to
 * defer to Intl's own default (the browser's own zone) - covers "browser"
 * mode with no special-casing, since that's already Intl.DateTimeFormat's
 * behavior when `timeZone` is omitted.
 */
export function resolveTimezone(recordZone) {
  if (preference.mode === 'manual') {
    return preference.manualZone || undefined;
  }
  if (preference.mode === 'clinic') {
    return recordZone || activeClinicZone || undefined;
  }
  return undefined;
}

export function supportedTimezones() {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    // Older engines without Intl.supportedValuesOf - manual mode just has
    // no list to pick from; browser/clinic modes are unaffected.
    return [];
  }
}
