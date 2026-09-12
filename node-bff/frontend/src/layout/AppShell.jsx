import { useAuth } from '../auth/AuthContext.jsx';
import { useMyClinic } from '../api/queries.js';

/**
 * Signed-in landing - just enough to confirm the whole chain end to end
 * (session -> /auth/me role, and for staff, GET /api/clinic/me through
 * TenantContextFilter) until each role gets its own dashboard in later
 * phases.
 */
export default function AppShell() {
  const { user, roles, hasRole } = useAuth();
  const isStaff = hasRole('clinic_admin') || hasRole('provider') || hasRole('front_desk');
  const { data: clinic, isLoading: clinicLoading, error: clinicError } = useMyClinic(isStaff);

  return (
    <div className="min-h-screen">
      <header className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
        <span className="font-semibold text-ink">Clinic Management</span>
        <form action="/auth/logout" method="post">
          <button type="submit" className="text-sm text-ink-muted hover:text-ink">
            Log out
          </button>
        </form>
      </header>
      <main className="mx-auto max-w-2xl px-6 py-10">
        <p className="text-sm text-ink-muted">Signed in as</p>
        <p className="text-lg font-medium text-ink">{user?.preferred_username ?? user?.email}</p>
        <p className="mt-1 text-sm text-ink-muted">Roles: {roles.length ? roles.join(', ') : 'none'}</p>

        {isStaff && (
          <div className="mt-6 rounded-md border border-slate-200 bg-white p-4">
            <p className="text-sm font-medium text-ink">Clinic</p>
            {clinicLoading && <p className="text-sm text-ink-muted">Loading...</p>}
            {clinicError && (
              <p className="text-sm text-danger">{clinicError.message || 'Could not load clinic.'}</p>
            )}
            {clinic && <p className="text-sm text-ink-muted">{clinic.name}</p>}
          </div>
        )}
      </main>
    </div>
  );
}
