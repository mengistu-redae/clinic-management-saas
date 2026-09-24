import { createContext, useContext, useEffect, useSyncExternalStore } from 'react';
import {
  getTimezoneSnapshot,
  subscribeTimezone,
  setTimezoneMode,
  setManualTimezone,
  setActiveClinicZone,
  resolveTimezone,
} from '../lib/timezone.js';

const TimezoneContext = createContext(null);

/**
 * Browser-local / clinic's-own / manually-picked timezone display
 * preference (frontend phase N) - same localStorage-only storage decision
 * and file-per-concern layout as ThemeProvider.jsx/LanguageProvider.jsx,
 * wrapped around the whole app in main.jsx for the same reason (applies to
 * logged-out public pages too, not just signed-in staff).
 *
 * Unlike Theme/Language, the underlying state lives in lib/timezone.js as a
 * plain module singleton rather than useState here - lib/format.js's
 * formatters need to read it from ordinary (non-component) code, the same
 * reason i18n.language is read directly rather than through context. This
 * component just bridges that singleton into React via useSyncExternalStore
 * so components can re-render on a change, mirroring the sibling providers'
 * public shape ({ mode, ..., setMode }) even though the plumbing differs.
 */
export function TimezoneProvider({ children }) {
  const snapshot = useSyncExternalStore(subscribeTimezone, getTimezoneSnapshot);

  const value = {
    mode: snapshot.mode,
    manualZone: snapshot.manualZone,
    activeClinicZone: snapshot.activeClinicZone,
    resolvedZone: resolveTimezone(),
    setMode: setTimezoneMode,
    setManualZone: setManualTimezone,
  };

  return <TimezoneContext.Provider value={value}>{children}</TimezoneContext.Provider>;
}

/** { mode: 'browser'|'clinic'|'manual', manualZone, activeClinicZone, resolvedZone, setMode, setManualZone } */
export function useTimezone() {
  return useContext(TimezoneContext);
}

/**
 * Registers the given IANA zone as the ambient "current clinic" for
 * "clinic" mode, for the duration this component is mounted - call from a
 * page/provider that knows which single clinic is currently in view (a
 * signed-in staff member's own clinic via BrandingProvider, or a public
 * booking-flow page scoped to one clinicId). Clears back to null on
 * unmount so it never leaks into whatever renders next.
 */
export function useActiveClinicZone(zone) {
  useEffect(() => {
    setActiveClinicZone(zone);
    return () => setActiveClinicZone(null);
  }, [zone]);
}
