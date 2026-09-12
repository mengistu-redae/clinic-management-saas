import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';

/**
 * UX-only gate - hides/redirects in the UI so a patient doesn't stumble into
 * a staff-only page and vice versa. The real authorization boundary is
 * entirely server-side (@PreAuthorize in spring-boot-api, already the
 * enforced check on every endpoint this app calls) - a determined user
 * bypassing this component gets 403s from the API, not unauthorized data.
 *
 * Pass either `role` (single) or `roles` (an array) - the eventual /lab
 * route tree (later phase) is reachable by PROVIDER and CLINIC_ADMIN alike
 * (identical backend permissions), unlike most other staff pages here which
 * are single-role.
 */
export default function RequireRole({ role, roles, children }) {
  const { isLoading, authenticated, hasRole } = useAuth();

  if (isLoading) {
    return null;
  }
  if (!authenticated) {
    window.location.href = '/auth/login';
    return null;
  }
  const allowed = roles ? roles.some(hasRole) : hasRole(role);
  if (!allowed) {
    return <Navigate to="/" replace />;
  }
  return children;
}
