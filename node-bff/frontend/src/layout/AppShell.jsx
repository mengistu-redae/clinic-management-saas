import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext.jsx';
import { useBranding } from '../theme/BrandingProvider.jsx';
import ThemeToggle from '../components/ThemeToggle.jsx';
import LanguageToggle from '../components/LanguageToggle.jsx';

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
  const { t } = useTranslation();
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
              <span>{branding?.displayName || t('app.brandFallback')}</span>
            </NavLink>
            <nav className="flex flex-wrap items-center gap-1">
              {hasRole('patient') && (
                <>
                  <NavLink to="/patient" end className={navLinkClass}>
                    {t('nav.dashboard')}
                  </NavLink>
                  <NavLink to="/book" className={navLinkClass}>
                    {t('nav.patient.bookAppointment')}
                  </NavLink>
                  <NavLink to="/my-appointments" className={navLinkClass}>
                    {t('nav.patient.myAppointments')}
                  </NavLink>
                  <NavLink to="/my-lab-orders" className={navLinkClass}>
                    {t('nav.patient.myLabOrders')}
                  </NavLink>
                </>
              )}
              {hasRole('front_desk') && (
                <>
                  <NavLink to="/front-desk" end className={navLinkClass}>
                    {t('nav.dashboard')}
                  </NavLink>
                  <NavLink to="/front-desk/patients" className={navLinkClass}>
                    {t('nav.frontDesk.bookWalkIn')}
                  </NavLink>
                  <NavLink to="/front-desk/appointments" className={navLinkClass}>
                    {t('nav.frontDesk.appointments')}
                  </NavLink>
                </>
              )}
              {hasRole('provider') && (
                <>
                  <NavLink to="/provider" end className={navLinkClass}>
                    {t('nav.dashboard')}
                  </NavLink>
                  <NavLink to="/lab-orders" className={navLinkClass}>
                    {t('nav.provider.labOrders')}
                  </NavLink>
                  <NavLink to="/referrals" className={navLinkClass}>
                    {t('nav.provider.referrals')}
                  </NavLink>
                </>
              )}
              {hasRole('clinic_admin') && (
                <>
                  <NavLink to="/clinic-admin" end className={navLinkClass}>
                    {t('nav.dashboard')}
                  </NavLink>
                  <NavLink to="/front-desk/appointments" className={navLinkClass}>
                    {t('nav.clinicAdmin.appointments')}
                  </NavLink>
                  <NavLink to="/clinic-admin/providers" className={navLinkClass}>
                    {t('nav.clinicAdmin.providers')}
                  </NavLink>
                  <NavLink to="/clinic-admin/rooms" className={navLinkClass}>
                    {t('nav.clinicAdmin.rooms')}
                  </NavLink>
                  <NavLink to="/clinic-admin/appointment-types" className={navLinkClass}>
                    {t('nav.clinicAdmin.appointmentTypes')}
                  </NavLink>
                  <NavLink to="/lab-orders" className={navLinkClass}>
                    {t('nav.clinicAdmin.labOrders')}
                  </NavLink>
                  <NavLink to="/referrals" className={navLinkClass}>
                    {t('nav.clinicAdmin.referrals')}
                  </NavLink>
                  <NavLink to="/clinic-admin/settings" className={navLinkClass}>
                    {t('nav.clinicAdmin.settings')}
                  </NavLink>
                </>
              )}
              {hasRole('platform_admin') && (
                <>
                  <NavLink to="/platform-admin" end className={navLinkClass}>
                    {t('nav.dashboard')}
                  </NavLink>
                  <NavLink to="/platform-admin/clinics" className={navLinkClass}>
                    {t('nav.platformAdmin.clinics')}
                  </NavLink>
                </>
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <LanguageToggle />
            <ThemeToggle />
            <div className="text-right leading-tight">
              <p className="text-sm font-medium text-ink">{user?.preferred_username}</p>
              {user?.email && <p className="text-xs text-ink-muted">{user.email}</p>}
            </div>
            <form method="post" action="/auth/logout">
              <button
                type="submit"
                className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100"
              >
                {t('logOut')}
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
