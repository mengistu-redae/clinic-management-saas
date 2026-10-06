import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { useAuth } from './AuthContext.jsx';
import { useMyClinics } from '../api/queries.js';
import { setActiveClinicId as setActiveClinicIdHeader } from '../api/client.js';

const STORAGE_KEY = 'clinicops.activeClinicId';

function readStoredClinicId() {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    // Private browsing / storage disabled - fall back to "no preference",
    // same defensive shape ThemeProvider already uses.
    return null;
  }
}

function storeClinicId(id) {
  try {
    if (id) {
      localStorage.setItem(STORAGE_KEY, id);
    } else {
      localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Nothing useful to do if storage is unavailable - the in-memory
    // state for this session still works, it just won't survive a reload.
  }
}

const ActiveClinicContext = createContext(null);

/**
 * Phase 45: tracks which clinic is "active" for a staff member who belongs
 * to more than one branch (Keycloak already supports this natively - see
 * TenantContextFilter's own javadoc). For the common case - a single
 * -branch login, which is every staff account before this phase - this
 * resolves to exactly one clinic and activeClinicId is never actually set
 * on the api/client.js header, so nothing changes.
 *
 * Sits inside AuthProvider (needs roles to know whether to fetch at all)
 * and outside BrandingProvider (branding needs to know the active clinic
 * to refetch on a branch switch) - see main.jsx's provider order.
 */
export function ActiveClinicProvider({ children }) {
  const { authenticated, hasRole } = useAuth();
  const isStaff =
    authenticated &&
    (hasRole('clinic_admin') ||
      hasRole('front_desk') ||
      hasRole('provider') ||
      hasRole('pharmacist') ||
      hasRole('accountant') ||
      hasRole('lab_technician') ||
      hasRole('imaging_technologist'));

  const { data: clinics } = useMyClinics(isStaff);
  const [activeClinicId, setActiveClinicIdState] = useState(readStoredClinicId);

  const hasMultipleClinics = (clinics?.length ?? 0) > 1;

  // Once the real clinic list is in, drop a stored id that no longer
  // applies (e.g. membership revoked) and only emit a header at all when
  // there's actually a choice to make - single-branch staff never sends
  // X-Active-Clinic-Id, matching today's exact server-side default.
  useEffect(() => {
    if (!clinics) {
      return;
    }
    const stillValid = clinics.some((c) => c.id === activeClinicId);
    if (!hasMultipleClinics) {
      if (activeClinicId !== null) {
        setActiveClinicIdState(null);
        storeClinicId(null);
      }
      return;
    }
    if (!stillValid) {
      const fallback = clinics[0]?.id ?? null;
      setActiveClinicIdState(fallback);
      storeClinicId(fallback);
    }
  }, [clinics, hasMultipleClinics, activeClinicId]);

  useEffect(() => {
    setActiveClinicIdHeader(hasMultipleClinics ? activeClinicId : null);
  }, [activeClinicId, hasMultipleClinics]);

  const value = useMemo(
    () => ({
      clinics: clinics ?? [],
      hasMultipleClinics,
      activeClinicId: hasMultipleClinics ? activeClinicId : null,
      setActiveClinicId: (id) => {
        setActiveClinicIdState(id);
        storeClinicId(id);
      },
    }),
    [clinics, hasMultipleClinics, activeClinicId]
  );

  return <ActiveClinicContext.Provider value={value}>{children}</ActiveClinicContext.Provider>;
}

export function useActiveClinic() {
  const ctx = useContext(ActiveClinicContext);
  if (!ctx) {
    throw new Error('useActiveClinic must be used within an ActiveClinicProvider');
  }
  return ctx;
}
