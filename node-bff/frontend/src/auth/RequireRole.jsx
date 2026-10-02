import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';

/**
 * UX-only gate - hides/redirects in the UI so a patient doesn't stumble into
 * a staff-only page and vice versa. The real authorization boundary is
 * entirely server-side (@PreAuthorize in spring-boot-api, already the
 * enforced check on every endpoint this app calls) - a determined user
 * bypassing this component gets 403s from the API, not unauthorized data.
 *
 * Pass either `role` (single) or `roles` (an array) - most staff pages here
 * are single-role, but e.g. `/lab-orders` is reachable by PROVIDER,
 * CLINIC_ADMIN, and LAB_TECHNICIAN alike even though their backend write
 * access on that same page now differs (lab module L1) - the component
 * hosted there gates individual actions itself, this wrapper only governs
 * page reachability.
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
