import { useTranslation } from 'react-i18next';
import IconMenu from '../components/IconMenu.jsx';
import UserMenu from '../components/UserMenu.jsx';
import LanguageToggle from '../components/LanguageToggle.jsx';
import ThemeToggle from '../components/ThemeToggle.jsx';
import TimezoneToggle from '../components/TimezoneToggle.jsx';
import { LanguageIcon, ThemeIcon, ClockIcon, MenuIcon, ChevronLeftIcon, ChevronRightIcon } from '../components/icons.jsx';

/**
 * Slim sticky header replacing AppShell's old brand+icons+user+nav two-row
 * header (frontend phase A) now that the sidebar owns navigation and brand.
 * A real pre-existing gap fixed along the way: TimezoneToggle.jsx (frontend
 * phase N) had no header IconMenu wired to it at all since the header menus
 * were last split back into separate Language/Theme icons - it's added here
 * as its own icon menu, same shape as the other two.
 */
export default function Topbar({ onOpenMobileMenu, collapsed, onToggleCollapsed }) {
  const { t } = useTranslation();

  return (
    <header className="sticky top-0 z-30 flex items-center justify-between gap-3 border-b border-slate-200 bg-surface px-4 py-2.5 sm:px-6">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onOpenMobileMenu}
          aria-label={t('sidebar.openMenu')}
          className="rounded-lg p-2 text-ink-muted hover:bg-slate-100 hover:text-ink lg:hidden"
        >
          <MenuIcon className="h-5 w-5" />
        </button>
        <button
          type="button"
          onClick={onToggleCollapsed}
          aria-label={collapsed ? t('sidebar.expand') : t('sidebar.collapse')}
          title={collapsed ? t('sidebar.expand') : t('sidebar.collapse')}
          className="hidden rounded-lg p-2 text-ink-muted hover:bg-slate-100 hover:text-ink lg:inline-flex"
        >
          {collapsed ? <ChevronRightIcon className="h-5 w-5" /> : <ChevronLeftIcon className="h-5 w-5" />}
        </button>
      </div>

      <div className="flex items-center gap-2">
        <IconMenu icon={<LanguageIcon className="h-5 w-5" />} label={t('language.label')}>
          <LanguageToggle />
        </IconMenu>
        <IconMenu icon={<ThemeIcon className="h-5 w-5" />} label={t('theme.label')}>
          <ThemeToggle />
        </IconMenu>
        <IconMenu icon={<ClockIcon className="h-5 w-5" />} label={t('timezone.label')}>
          <TimezoneToggle />
        </IconMenu>
        <UserMenu />
      </div>
    </header>
  );
}
