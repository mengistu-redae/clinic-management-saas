import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

const tabClass = ({ isActive }) =>
  `border-b-2 px-1 pb-2 text-sm font-medium transition-colors ${
    isActive ? 'border-brand text-brand' : 'border-transparent text-ink-muted hover:text-ink'
  }`;

/**
 * clinic-admin config hub - one nav entry ("Settings"), four tabs, each its
 * own deep-linkable route (/clinic-admin/settings[/branding|/fee-policies
 * |/lab-rates]). Ported from the reference bus-ticketing-saas project's own
 * operator/SettingsLayout.jsx - same tab-hub shape, this app's own resource
 * names.
 */
export default function ClinicAdminSettingsLayout() {
  const { t } = useTranslation();
  return (
    <div>
      <h1 className="mb-4 text-2xl font-bold text-ink">{t('nav.clinicAdmin.settings')}</h1>
      <nav className="mb-6 flex gap-6 border-b border-slate-200">
        <NavLink to="/clinic-admin/settings" end className={tabClass}>
          {t('clinicAdminSettingsTabs.general')}
        </NavLink>
        <NavLink to="/clinic-admin/settings/branding" className={tabClass}>
          {t('clinicAdminSettingsTabs.branding')}
        </NavLink>
        <NavLink to="/clinic-admin/settings/fee-policies" className={tabClass}>
          {t('clinicAdminSettingsTabs.feePolicies')}
        </NavLink>
        <NavLink to="/clinic-admin/settings/lab-rates" className={tabClass}>
          {t('clinicAdminSettingsTabs.labRates')}
        </NavLink>
      </nav>
      <Outlet />
    </div>
  );
}
