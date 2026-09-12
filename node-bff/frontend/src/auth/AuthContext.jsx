import { createContext, useContext, useMemo } from 'react';
import { useAuthMe } from '../api/queries.js';

const AuthContext = createContext(null);

/**
 * Wraps GET /auth/me. Role info comes from req.session.user, merged from the
 * access token's realm_access.roles in the BFF's /auth/callback (the ID
 * token doesn't reliably carry it - see routes/auth.js). hasRole() is
 * written defensively either way: no roles claim just means every hasRole()
 * check returns false, degrading to "show nothing role-gated" rather than
 * throwing.
 */
export function AuthProvider({ children }) {
  const { data, isLoading } = useAuthMe();

  const value = useMemo(() => {
    const authenticated = Boolean(data?.authenticated);
    const user = authenticated ? data.user : null;
    const roles = (user?.realm_access?.roles || []).map((r) => r.toLowerCase());
    return {
      isLoading,
      authenticated,
      user,
      roles,
      hasRole: (role) => roles.includes(role.toLowerCase()),
    };
  }, [data, isLoading]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
