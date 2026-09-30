import { NavLink } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext.jsx';
import { useBranding } from '../theme/BrandingProvider.jsx';
import {
  DashboardIcon,
  CalendarIcon,
  UsersIcon,
  FlaskIcon,
  ClipboardIcon,
  BuildingIcon,
  SettingsIcon,
  CloseIcon,
  WalletIcon,
  ReceiptIcon,
  BoxIcon,
  WrenchIcon,
} from '../components/icons.jsx';

/**
 * Role-aware nav, re-shelved from AppShell.jsx's old horizontal nav row
 * (frontend phase A) into a vertical sidebar - same hasRole() branches, same
 * nav.* i18n keys, just a different container. `collapsed` (desktop,
 * persisted in AppShell) shows icon-only with a title tooltip; `mobileOpen`
 * (small screens) renders this as an off-canvas drawer over a backdrop,
 * closing itself on a link click via `onNavigate`.
 */
function navGroups(t, hasRole) {
  const groups = [];
  if (hasRole('patient')) {
    groups.push({
      items: [
        { to: '/patient', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/book', label: t('nav.patient.bookAppointment'), icon: CalendarIcon },
        { to: '/my-appointments', label: t('nav.patient.myAppointments'), icon: ClipboardIcon },
        { to: '/my-lab-orders', label: t('nav.patient.myLabOrders'), icon: FlaskIcon },
      ],
    });
  }
  if (hasRole('front_desk')) {
    groups.push({
      items: [
        { to: '/front-desk', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/front-desk/patients', label: t('nav.frontDesk.bookWalkIn'), icon: UsersIcon },
        { to: '/front-desk/appointments', label: t('nav.frontDesk.appointments'), icon: CalendarIcon },
        { to: '/inventory', end: true, label: t('inventoryPage.dashboardTitle'), icon: BoxIcon },
        { to: '/inventory/items', label: t('nav.inventory.items'), icon: BoxIcon },
        { to: '/inventory/suppliers', label: t('nav.inventory.suppliers'), icon: BuildingIcon },
        { to: '/inventory/purchase-orders', label: t('nav.inventory.purchaseOrders'), icon: ReceiptIcon },
        { to: '/inventory/assets', label: t('nav.inventory.assets'), icon: WrenchIcon },
      ],
    });
  }
  if (hasRole('provider')) {
    groups.push({
      items: [
        { to: '/provider', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/lab-orders', label: t('nav.provider.labOrders'), icon: FlaskIcon },
        { to: '/referrals', label: t('nav.provider.referrals'), icon: ClipboardIcon },
      ],
    });
  }
  if (hasRole('clinic_admin')) {
    groups.push({
      items: [
        { to: '/clinic-admin', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/front-desk/appointments', label: t('nav.clinicAdmin.appointments'), icon: CalendarIcon },
        { to: '/clinic-admin/providers', label: t('nav.clinicAdmin.providers'), icon: UsersIcon },
        { to: '/clinic-admin/rooms', label: t('nav.clinicAdmin.rooms'), icon: BuildingIcon },
        { to: '/clinic-admin/appointment-types', label: t('nav.clinicAdmin.appointmentTypes'), icon: ClipboardIcon },
        { to: '/lab-orders', label: t('nav.clinicAdmin.labOrders'), icon: FlaskIcon },
        { to: '/referrals', label: t('nav.clinicAdmin.referrals'), icon: ClipboardIcon },
        { to: '/pharmacist/medications', label: t('nav.pharmacist.medications'), icon: FlaskIcon },
        { to: '/pharmacist/drug-interactions', label: t('nav.pharmacist.drugInteractions'), icon: ClipboardIcon },
        { to: '/accountant/accounts', label: t('nav.accountant.accounts'), icon: WalletIcon },
        { to: '/accountant/payroll', label: t('nav.accountant.payroll'), icon: ReceiptIcon },
        { to: '/inventory', end: true, label: t('inventoryPage.dashboardTitle'), icon: BoxIcon },
        { to: '/inventory/items', label: t('nav.inventory.items'), icon: BoxIcon },
        { to: '/inventory/suppliers', label: t('nav.inventory.suppliers'), icon: BuildingIcon },
        { to: '/inventory/purchase-orders', label: t('nav.inventory.purchaseOrders'), icon: ReceiptIcon },
        { to: '/inventory/assets', label: t('nav.inventory.assets'), icon: WrenchIcon },
        { to: '/clinic-admin/settings', label: t('nav.clinicAdmin.settings'), icon: SettingsIcon },
      ],
    });
  }
  if (hasRole('pharmacist')) {
    groups.push({
      items: [
        { to: '/pharmacist', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/pharmacist/medications', label: t('nav.pharmacist.medications'), icon: FlaskIcon },
        { to: '/pharmacist/drug-interactions', label: t('nav.pharmacist.drugInteractions'), icon: ClipboardIcon },
      ],
    });
  }
  if (hasRole('accountant')) {
    groups.push({
      items: [
        { to: '/accountant', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/accountant/accounts', label: t('nav.accountant.accounts'), icon: WalletIcon },
        { to: '/accountant/journal', label: t('nav.accountant.journal'), icon: ClipboardIcon },
        { to: '/accountant/employees', label: t('nav.accountant.employees'), icon: UsersIcon },
        { to: '/accountant/payroll', label: t('nav.accountant.payroll'), icon: ReceiptIcon },
        { to: '/accountant/budgets', label: t('nav.accountant.budgets'), icon: ClipboardIcon },
      ],
    });
  }
  if (hasRole('platform_admin')) {
    groups.push({
      items: [
        { to: '/platform-admin', end: true, label: t('nav.dashboard'), icon: DashboardIcon },
        { to: '/platform-admin/clinics', label: t('nav.platformAdmin.clinics'), icon: BuildingIcon },
      ],
    });
  }
  return groups;
}

function itemClass(collapsed) {
  return ({ isActive }) =>
    `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
      collapsed ? 'justify-center' : ''
    } ${isActive ? 'bg-brand-light text-brand-text' : 'text-ink-muted hover:bg-slate-100 hover:text-ink'}`;
}

function SidebarContent({ collapsed, onNavigate }) {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const groups = navGroups(t, hasRole);

  return (
    <nav className="flex flex-1 flex-col gap-1 overflow-y-auto px-2 py-3">
      {groups.map((group, i) => (
        <div key={i} className="flex flex-col gap-1">
          {group.items.map((item) => (
            <NavLink
              key={item.to + item.label}
              to={item.to}
              end={item.end}
              onClick={onNavigate}
              title={collapsed ? item.label : undefined}
              className={itemClass(collapsed)}
            >
              <item.icon className="h-5 w-5 shrink-0" />
              {!collapsed && <span className="truncate">{item.label}</span>}
            </NavLink>
          ))}
        </div>
      ))}
    </nav>
  );
}

export default function Sidebar({ collapsed, mobileOpen, onCloseMobile }) {
  const { t } = useTranslation();
  const branding = useBranding();

  return (
    <>
      {/* Desktop/tablet: a permanent column, width toggles between full and icon-only. */}
      <aside
        className={`hidden shrink-0 border-r border-slate-200 bg-surface transition-all duration-200 lg:flex lg:flex-col ${
          collapsed ? 'lg:w-16' : 'lg:w-60'
        }`}
      >
        <BrandRow branding={branding} collapsed={collapsed} />
        <SidebarContent collapsed={collapsed} />
      </aside>

      {/* Mobile/tablet: an off-canvas drawer + backdrop, only mounted while open. */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 flex lg:hidden">
          <div className="fixed inset-0 bg-black/40" onClick={onCloseMobile} aria-hidden="true" />
          <aside className="relative flex w-72 max-w-[85vw] flex-col bg-surface shadow-xl">
            <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
              <BrandRow branding={branding} collapsed={false} bare />
              <button
                type="button"
                onClick={onCloseMobile}
                aria-label={t('sidebar.closeMenu')}
                className="rounded-lg p-2 text-ink-muted hover:bg-slate-100 hover:text-ink"
              >
                <CloseIcon className="h-5 w-5" />
              </button>
            </div>
            <SidebarContent collapsed={false} onNavigate={onCloseMobile} />
          </aside>
        </div>
      )}
    </>
  );
}

function BrandRow({ branding, collapsed, bare = false }) {
  const { t } = useTranslation();
  return (
    <div className={`flex items-center gap-2 ${bare ? '' : 'border-b border-slate-200 px-4 py-4'} ${collapsed ? 'justify-center px-2' : ''}`}>
      {branding?.logoUrl && <img src={branding.logoUrl} alt="" className="h-7 w-auto max-w-[8rem] object-contain" />}
      {!collapsed && <span className="truncate text-lg font-bold text-brand-text">{branding?.displayName || t('app.brandFallback')}</span>}
    </div>
  );
}
