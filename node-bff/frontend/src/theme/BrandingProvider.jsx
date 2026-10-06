import { createContext, useContext, useEffect } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import { useActiveClinic } from '../auth/ActiveClinicContext.jsx';
import { useClinicBranding } from '../api/queries.js';
import { themeVars } from '../lib/color.js';
import { useTheme } from './ThemeProvider.jsx';
import { useActiveClinicZone } from './TimezoneProvider.jsx';

const BrandingContext = createContext(null);

const VARS = [
  '--brand', '--brand-dark', '--brand-light', '--brand-text',
  '--accent', '--accent-dark', '--accent-light', '--accent-text',
];

/**
 * Themes the staff workspace (clinic_admin / front_desk / provider /
 * pharmacist / accountant - the latter two added in phase 20, which also
 * widened ClinicBrandingController's own @PreAuthorize to match, otherwise
 * these two new roles would 403 fetching their own clinic's branding) with
 * the signed-in user's own clinic branding: fetches GET /api/clinic/branding
 * (matches that endpoint's own @PreAuthorize - platform_admin isn't tied to
 * a single clinic, patient stays on the platform default) and writes the
 * brand/accent CSS vars onto document.documentElement. Closes a loose end
 * already flagged in tailwind.config.js/index.css's own comments ("a future
 * BrandingProvider overrides them... for a signed-in clinic's staff").
 * Ported from the reference bus-ticketing-saas project's own
 * theme/BrandingProvider.jsx.
 */
export function BrandingProvider({ children }) {
  const { authenticated, hasRole } = useAuth();
  const isStaff = authenticated && (hasRole('clinic_admin') || hasRole('front_desk') || hasRole('provider') || hasRole('pharmacist') || hasRole('accountant'));

  // activeClinicId (phase 45) is only non-null for a staff member who
  // belongs to more than one branch - it's part of useClinicBranding's own
  // query key purely so switching branches triggers a refetch of THIS
  // clinic's own branding/timezone, closing the "a signed-in staff member
  // only ever has one clinic" assumption this used to hard-code.
  const { activeClinicId } = useActiveClinic();
  const { data } = useClinicBranding(isStaff, activeClinicId);
  // ThemeProvider must wrap this in main.jsx - resolvedTheme feeds
  // themeVars() below so a clinic's own brand/accent -light badge tint
  // mixes toward black (dark theme) or white (light theme) to match
  // whichever card it's actually rendered on. Re-runs on every theme
  // switch, not just on branding load - see lib/color.js's deriveShades.
  const { resolvedTheme } = useTheme();

  // Registers the active clinic's own timezone as the ambient "current
  // clinic" for the timezone preference's "clinic" mode
  // (theme/TimezoneProvider.jsx) - re-registers on every branch switch too.
  useActiveClinicZone(isStaff ? data?.timezone : null);

  useEffect(() => {
    const root = document.documentElement;
    if (!isStaff || !data) {
      VARS.forEach((v) => root.style.removeProperty(v));
      return undefined;
    }
    const vars = {
      ...themeVars(data.brandColor, 'brand', resolvedTheme),
      ...themeVars(data.accentColor, 'accent', resolvedTheme),
    };
    Object.entries(vars).forEach(([k, val]) => root.style.setProperty(k, val));
    return () => VARS.forEach((v) => root.style.removeProperty(v));
  }, [isStaff, data, resolvedTheme]);

  return (
    <BrandingContext.Provider value={isStaff ? (data ?? null) : null}>
      {children}
    </BrandingContext.Provider>
  );
}

/** Staff-workspace branding ({ displayName, footerNote, logoUrl, brandColor, accentColor }) or null. */
export function useBranding() {
  return useContext(BrandingContext);
}
