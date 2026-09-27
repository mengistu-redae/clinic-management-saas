import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext.jsx';
import IconMenu from './IconMenu.jsx';
import { UserCircleIcon, LogoutIcon } from './icons.jsx';

/**
 * Replaces the old always-visible "name + email + Logout button" block
 * (AppShell.jsx, phase A) with one IconMenu-based menu - reclaims header
 * width now that the sidebar owns nav, same click-outside/Escape mechanism
 * the language/theme menus already use.
 */
export default function UserMenu() {
  const { t } = useTranslation();
  const { user } = useAuth();

  return (
    <IconMenu icon={<UserCircleIcon className="h-5 w-5" />} label={user?.preferred_username || t('userMenu.account')}>
      <div className="flex flex-col gap-3">
        <div className="leading-tight">
          <p className="text-sm font-medium text-ink">{user?.preferred_username}</p>
          {user?.email && <p className="text-xs text-ink-muted">{user.email}</p>}
        </div>
        <form method="post" action="/auth/logout">
          <button
            type="submit"
            className="flex w-full items-center gap-2 rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink"
          >
            <LogoutIcon className="h-4 w-4" />
            {t('logOut')}
          </button>
        </form>
      </div>
    </IconMenu>
  );
}
