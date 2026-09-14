import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useBranding } from '../theme/BrandingProvider.jsx';

const navLinkClass = ({ isActive }) =>
  `rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
    isActive ? 'bg-brand-light text-brand' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'
  }`;

/**
 * Role-aware nav shell (real routing/layout, replacing the phase-1 stub) -
 * ported from the reference bus-ticketing-saas project's own
 * layout/AppShell.jsx. Each role's nav is deliberately just its one
 * "Dashboard" link this phase (already where "/" lands it) - more links
 * arrive as each role's deeper pages get built in later frontend phases.
 */
export default function AppShell() {
  const { user, hasRole } = useAuth();
  const branding = useBranding();

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-y-2 px-4 py-3 sm:px-6">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 sm:gap-x-8">
            <NavLink to="/" className="flex items-center gap-2 text-lg font-bold text-brand">
              {branding?.logoUrl && (
                <img src={branding.logoUrl} alt="" className="h-7 w-auto max-w-[8rem] object-contain" />
              )}
              <span>{branding?.displayName || 'Clinic Management'}</span>
            </NavLink>
            <nav className="flex flex-wrap items-center gap-1">
              {hasRole('patient') && (
                <>
                  <NavLink to="/patient" end className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/book" className={navLinkClass}>
                    Book an appointment
                  </NavLink>
                  <NavLink to="/my-appointments" className={navLinkClass}>
                    My Appointments
                  </NavLink>
                </>
              )}
              {hasRole('front_desk') && (
                <>
                  <NavLink to="/front-desk" end className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/front-desk/patients" className={navLinkClass}>
                    Book for a walk-in
                  </NavLink>
                  <NavLink to="/front-desk/appointments" className={navLinkClass}>
                    Appointments
                  </NavLink>
                </>
              )}
              {hasRole('provider') && (
                <NavLink to="/provider" className={navLinkClass}>
                  Dashboard
                </NavLink>
              )}
              {hasRole('clinic_admin') && (
                <>
                  <NavLink to="/clinic-admin" end className={navLinkClass}>
                    Dashboard
                  </NavLink>
                  <NavLink to="/clinic-admin/providers" className={navLinkClass}>
                    Providers
                  </NavLink>
                  <NavLink to="/clinic-admin/rooms" className={navLinkClass}>
                    Rooms
                  </NavLink>
                  <NavLink to="/clinic-admin/appointment-types" className={navLinkClass}>
                    Appointment Types
                  </NavLink>
                  <NavLink to="/clinic-admin/settings" className={navLinkClass}>
                    Settings
                  </NavLink>
                </>
              )}
              {hasRole('platform_admin') && (
                <NavLink to="/platform-admin" className={navLinkClass}>
                  Dashboard
                </NavLink>
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <div className="text-right leading-tight">
              <p className="text-sm font-medium text-ink">{user?.preferred_username}</p>
              {user?.email && <p className="text-xs text-ink-muted">{user.email}</p>}
            </div>
            <form method="post" action="/auth/logout">
              <button
                type="submit"
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100"
              >
                Log out
              </button>
            </form>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
