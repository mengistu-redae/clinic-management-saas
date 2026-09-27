import { Link, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import IconMenu from '../components/IconMenu.jsx';
import LanguageToggle from '../components/LanguageToggle.jsx';
import ThemeToggle from '../components/ThemeToggle.jsx';
import TimezoneToggle from '../components/TimezoneToggle.jsx';
import { LanguageIcon, ThemeIcon, ClockIcon } from '../components/icons.jsx';

/**
 * Logged-out shell - restyled to match AppShell/Topbar's new sticky, slim
 * header (same height/spacing/icon-menu treatment) for consistency between
 * logged-out and logged-in chrome, per the modern-UI redesign. Stays a
 * plain top bar (no sidebar) - 3 public links + login never justified one.
 * Also gains the TimezoneToggle icon menu Topbar.jsx restores (see its own
 * comment - it had been orphaned since the header menus were last split).
 */
export default function PublicShell() {
  const { t } = useTranslation();

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="sticky top-0 z-30 border-b border-slate-200 bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-y-2 px-4 py-2.5 sm:px-6">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 sm:gap-x-8">
            <Link to="/" className="text-lg font-bold text-brand-text">
              {t('app.brandFallback')}
            </Link>
            <nav className="flex flex-wrap items-center gap-1">
              <Link to="/book" className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink">
                {t('publicNav.bookAppointment')}
              </Link>
              <Link to="/track-appointment" className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink">
                {t('publicNav.trackAppointment')}
              </Link>
              <Link to="/track-lab-order" className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink">
                {t('publicNav.trackLabOrder')}
              </Link>
            </nav>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <IconMenu icon={<LanguageIcon className="h-5 w-5" />} label={t('language.label')}>
              <LanguageToggle />
            </IconMenu>
            <IconMenu icon={<ThemeIcon className="h-5 w-5" />} label={t('theme.label')}>
              <ThemeToggle />
            </IconMenu>
            <IconMenu icon={<ClockIcon className="h-5 w-5" />} label={t('timezone.label')}>
              <TimezoneToggle />
            </IconMenu>
            <a href="/auth/login" className="rounded-lg bg-brand px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-dark">
              {t('publicNav.logIn')}
            </a>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
