import { Routes, Route, Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './auth/AuthContext.jsx';
import RequireRole from './auth/RequireRole.jsx';
import AppShell from './layout/AppShell.jsx';
import PublicShell from './layout/PublicShell.jsx';
import PatientDashboard from './pages/patient/Dashboard.jsx';
import FrontDeskDashboard from './pages/front-desk/Dashboard.jsx';
import ProviderDashboard from './pages/provider/Dashboard.jsx';
import ClinicAdminDashboard from './pages/clinic-admin/Dashboard.jsx';
import PlatformAdminDashboard from './pages/platform-admin/Dashboard.jsx';
import ClinicPicker from './pages/booking/ClinicPicker.jsx';
import BookingForm from './pages/booking/BookingForm.jsx';
import AppointmentDetail from './pages/AppointmentDetail.jsx';
import Reschedule from './pages/Reschedule.jsx';
import MyAppointments from './pages/MyAppointments.jsx';
import TrackAppointment from './pages/TrackAppointment.jsx';
import FrontDeskPatientSearch from './pages/front-desk/PatientSearch.jsx';
import FrontDeskBookForPatient from './pages/front-desk/BookForPatient.jsx';
import FrontDeskAppointments from './pages/front-desk/Appointments.jsx';
import FrontDeskAppointmentDetail from './pages/front-desk/AppointmentDetail.jsx';
import FrontDeskReschedule from './pages/front-desk/Reschedule.jsx';
import ClinicAdminProviders from './pages/clinic-admin/Providers.jsx';
import ClinicAdminRooms from './pages/clinic-admin/Rooms.jsx';
import ClinicAdminAppointmentTypes from './pages/clinic-admin/AppointmentTypes.jsx';
import ClinicAdminSettingsLayout from './pages/clinic-admin/SettingsLayout.jsx';
import ClinicAdminSettings from './pages/clinic-admin/Settings.jsx';
import ClinicAdminBranding from './pages/clinic-admin/Branding.jsx';
import ClinicAdminFeePolicies from './pages/clinic-admin/FeePolicies.jsx';
import ClinicAdminLabRates from './pages/clinic-admin/LabRates.jsx';
import ProviderEncounter from './pages/provider/Encounter.jsx';
import LabOrders from './pages/lab-orders/LabOrders.jsx';
import LabOrderDetail from './pages/lab-orders/LabOrderDetail.jsx';
import Referrals from './pages/referrals/Referrals.jsx';
import RequestLabTest from './pages/patient/RequestLabTest.jsx';
import MyLabOrders from './pages/patient/MyLabOrders.jsx';
import MyLabOrderDetail from './pages/patient/MyLabOrderDetail.jsx';
import TrackLabOrder from './pages/TrackLabOrder.jsx';
import PlatformAdminClinics from './pages/platform-admin/Clinics.jsx';
import PharmacistDashboard from './pages/pharmacist/Dashboard.jsx';
import PharmacistMedications from './pages/pharmacist/Medications.jsx';
import AccountantDashboard from './pages/accountant/Dashboard.jsx';
import AccountantAccounts from './pages/accountant/Accounts.jsx';
import AccountantJournal from './pages/accountant/Journal.jsx';
import AccountantEmployees from './pages/accountant/Employees.jsx';
import AccountantPayroll from './pages/accountant/Payroll.jsx';
import AccountantBudgets from './pages/accountant/Budgets.jsx';
import InventoryDashboard from './pages/inventory/Dashboard.jsx';
import InventoryItems from './pages/inventory/Items.jsx';
import InventorySuppliers from './pages/inventory/Suppliers.jsx';
import InventoryPurchaseOrders from './pages/inventory/PurchaseOrders.jsx';
import InventoryAssets from './pages/inventory/Assets.jsx';

/**
 * Logged-out landing at "/" - previously title+subtitle only, with every
 * actual action (book/track/log in) reachable solely from PublicShell's
 * small header nav. Real CTAs added here so the body of the page - not
 * just its header - carries the wayfinding: a primary "Book an
 * appointment" button, two secondary track-something cards restating
 * (not replacing) the header nav links, and a plain sign-in line for
 * returning staff/patients.
 */
function PublicHome() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-center gap-10 py-12 text-center">
      <div className="flex flex-col items-center gap-3">
        <h1 className="text-3xl font-bold text-ink">{t('publicHome.title')}</h1>
        <p className="max-w-md text-sm text-ink-muted">{t('publicHome.subtitle')}</p>
      </div>

      <Link
        to="/book"
        className="rounded-lg bg-brand px-6 py-3 text-base font-semibold text-white hover:bg-brand-dark"
      >
        {t('publicNav.bookAppointment')}
      </Link>

      <div className="grid w-full max-w-3xl gap-4 sm:grid-cols-2">
        <Link
          to="/track-appointment"
          className="rounded-xl border border-slate-200 bg-surface p-5 text-left hover:border-brand/40 hover:bg-slate-50"
        >
          <p className="text-sm font-semibold text-ink">{t('publicNav.trackAppointment')}</p>
          <p className="mt-1 text-xs text-ink-muted">{t('publicHome.trackAppointmentHint')}</p>
        </Link>
        <Link
          to="/track-lab-order"
          className="rounded-xl border border-slate-200 bg-surface p-5 text-left hover:border-brand/40 hover:bg-slate-50"
        >
          <p className="text-sm font-semibold text-ink">{t('publicNav.trackLabOrder')}</p>
          <p className="mt-1 text-xs text-ink-muted">{t('publicHome.trackLabOrderHint')}</p>
        </Link>
      </div>

      <p className="text-xs text-ink-muted">
        {t('publicHome.haveAccount')}{' '}
        <a href="/auth/login" className="font-medium text-brand-text hover:underline">
          {t('publicNav.logIn')}
        </a>
        .
      </p>
    </div>
  );
}

/**
 * "/" is role-aware: each signed-in role lands on its own dashboard; a
 * logged-out visitor gets PublicHome (via RootLayout below, inside
 * PublicShell). Real authorization stays entirely server-side
 * (@PreAuthorize on every endpoint these dashboards call) - this and
 * RequireRole are UX routing only. Mirrors the reference bus-ticketing
 * -saas project's own App.jsx RootLayout/RoleHome shape, adapted to this
 * app's five roles.
 */
function RoleHome() {
  const { authenticated, hasRole } = useAuth();
  if (!authenticated) return <PublicHome />;
  if (hasRole('patient')) return <PatientDashboard />;
  if (hasRole('front_desk')) return <FrontDeskDashboard />;
  if (hasRole('provider')) return <ProviderDashboard />;
  if (hasRole('clinic_admin')) return <ClinicAdminDashboard />;
  if (hasRole('platform_admin')) return <PlatformAdminDashboard />;
  if (hasRole('pharmacist')) return <PharmacistDashboard />;
  if (hasRole('accountant')) return <AccountantDashboard />;
  return null;
}

function RootLayout() {
  const { isLoading, authenticated } = useAuth();
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-brand-light border-t-brand" />
      </div>
    );
  }
  return authenticated ? <AppShell /> : <PublicShell />;
}

function NotFound() {
  const { t } = useTranslation();
  return (
    <div className="py-16 text-center">
      <p className="text-xl font-semibold text-ink">{t('notFound.title')}</p>
      <Link to="/" className="mt-2 inline-block text-sm text-brand-text hover:underline">
        {t('notFound.backHome')}
      </Link>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route element={<RootLayout />}>
        <Route path="/" element={<RoleHome />} />
        {/* Stable deep-links per role, matching the nav's own targets -
            RoleHome renders these at "/" too, but a bookmarkable path is
            clearer and is where future per-role pages will nest under. */}
        <Route
          path="/patient"
          element={
            <RequireRole role="patient">
              <PatientDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk"
          element={
            <RequireRole role="front_desk">
              <FrontDeskDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/provider"
          element={
            <RequireRole role="provider">
              <ProviderDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/provider/appointments/:id/encounter"
          element={
            <RequireRole roles={['provider', 'clinic_admin']}>
              <ProviderEncounter />
            </RequireRole>
          }
        />
        <Route
          path="/clinic-admin"
          element={
            <RequireRole role="clinic_admin">
              <ClinicAdminDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/platform-admin"
          element={
            <RequireRole role="platform_admin">
              <PlatformAdminDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/platform-admin/clinics"
          element={
            <RequireRole role="platform_admin">
              <PlatformAdminClinics />
            </RequireRole>
          }
        />
        {/* Booking flow - public (guest) and patient alike, same as the
            reference's /search, /trips/:id, /bookings/:id, /track-booking. */}
        <Route path="/book" element={<ClinicPicker />} />
        <Route path="/book/:clinicId" element={<BookingForm />} />
        <Route path="/appointments/:id" element={<AppointmentDetail />} />
        <Route path="/track-appointment" element={<TrackAppointment />} />
        <Route path="/track-lab-order" element={<TrackLabOrder />} />
        <Route
          path="/appointments/:id/reschedule"
          element={
            <RequireRole role="patient">
              <Reschedule />
            </RequireRole>
          }
        />
        <Route
          path="/my-appointments"
          element={
            <RequireRole role="patient">
              <MyAppointments />
            </RequireRole>
          }
        />
        <Route
          path="/my-lab-orders"
          element={
            <RequireRole role="patient">
              <MyLabOrders />
            </RequireRole>
          }
        />
        <Route
          path="/my-lab-orders/request"
          element={
            <RequireRole role="patient">
              <RequestLabTest />
            </RequireRole>
          }
        />
        <Route
          path="/my-lab-orders/:id"
          element={
            <RequireRole role="patient">
              <MyLabOrderDetail />
            </RequireRole>
          }
        />
        {/* Front-desk workflow - staff-only, no guest/public angle. Patient
            search/registration and booking stay front_desk-only (matches
            PatientController's own write role); the appointments list/
            detail/reschedule pages are shared with clinic_admin too - every
            action they drive (CheckInController/CancellationController/
            RescheduleController/LabOrderPaymentController) already permits
            CLINIC_ADMIN server-side, so this just gives clinic_admin a UI
            path in, rather than duplicating the page tree. */}
        <Route
          path="/front-desk/patients"
          element={
            <RequireRole role="front_desk">
              <FrontDeskPatientSearch />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/book/:patientId"
          element={
            <RequireRole role="front_desk">
              <FrontDeskBookForPatient />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/appointments"
          element={
            <RequireRole roles={['front_desk', 'clinic_admin']}>
              <FrontDeskAppointments />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/appointments/:id"
          element={
            <RequireRole roles={['front_desk', 'clinic_admin']}>
              <FrontDeskAppointmentDetail />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/appointments/:id/reschedule"
          element={
            <RequireRole roles={['front_desk', 'clinic_admin']}>
              <FrontDeskReschedule />
            </RequireRole>
          }
        />
        {/* Clinic-admin config - staff-only, mirrors the reference project's
            operator_admin fleet-management + settings-hub shape. */}
        <Route
          path="/clinic-admin/providers"
          element={
            <RequireRole role="clinic_admin">
              <ClinicAdminProviders />
            </RequireRole>
          }
        />
        <Route
          path="/clinic-admin/rooms"
          element={
            <RequireRole role="clinic_admin">
              <ClinicAdminRooms />
            </RequireRole>
          }
        />
        <Route
          path="/clinic-admin/appointment-types"
          element={
            <RequireRole role="clinic_admin">
              <ClinicAdminAppointmentTypes />
            </RequireRole>
          }
        />
        <Route
          path="/clinic-admin/settings"
          element={
            <RequireRole role="clinic_admin">
              <ClinicAdminSettingsLayout />
            </RequireRole>
          }
        >
          <Route index element={<ClinicAdminSettings />} />
          <Route path="branding" element={<ClinicAdminBranding />} />
          <Route path="fee-policies" element={<ClinicAdminFeePolicies />} />
          <Route path="lab-rates" element={<ClinicAdminLabRates />} />
        </Route>
        {/* Lab orders - shared by provider and clinic_admin alike (identical
            backend permissions on every LabOrderController/
            LabOrderStatusController/LabOrderCancellationController/
            PatientLabRequestController endpoint), one route tree instead of
            duplicating it per role. */}
        <Route
          path="/lab-orders"
          element={
            <RequireRole roles={['provider', 'clinic_admin']}>
              <LabOrders />
            </RequireRole>
          }
        />
        <Route
          path="/lab-orders/:id"
          element={
            <RequireRole roles={['provider', 'clinic_admin']}>
              <LabOrderDetail />
            </RequireRole>
          }
        />
        {/* Referrals - same shared-route-tree reasoning as lab orders above (frontend phase L). */}
        <Route
          path="/referrals"
          element={
            <RequireRole roles={['provider', 'clinic_admin']}>
              <Referrals />
            </RequireRole>
          }
        />
        {/* Pharmacy (phase 20) - pharmacist's own worklist/catalog, shared
            with clinic_admin (its own PreAuthorize override, same reasoning
            as every other module here). */}
        <Route
          path="/pharmacist"
          element={
            <RequireRole roles={['pharmacist', 'clinic_admin']}>
              <PharmacistDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/pharmacist/medications"
          element={
            <RequireRole roles={['pharmacist', 'clinic_admin']}>
              <PharmacistMedications />
            </RequireRole>
          }
        />
        {/* Accounting/finance (phases 21/22) - every endpoint behind these
            pages already grants clinic_admin full override access on the
            backend (no ownership check, same as every other module), so
            every route here allows both roles too - same "full route
            parity, curated nav" shape already established for pharmacist
            (clinic_admin's own sidebar only links Accounts + Payroll, not
            all six, since clinic_admin already has its own Dashboard). */}
        <Route
          path="/accountant"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/accountant/accounts"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantAccounts />
            </RequireRole>
          }
        />
        <Route
          path="/accountant/journal"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantJournal />
            </RequireRole>
          }
        />
        <Route
          path="/accountant/employees"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantEmployees />
            </RequireRole>
          }
        />
        <Route
          path="/accountant/payroll"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantPayroll />
            </RequireRole>
          }
        />
        <Route
          path="/accountant/budgets"
          element={
            <RequireRole roles={['accountant', 'clinic_admin']}>
              <AccountantBudgets />
            </RequireRole>
          }
        />
        {/* General inventory (phases 29/30/34) - a co-equal two-role gate,
            not a primary-role-plus-clinic_admin-override the way
            pharmacist/accountant work: the backend already grants
            clinic_admin and front_desk full, symmetric access with no
            ownership distinction, so there's no "curated subset" split
            needed here. */}
        <Route
          path="/inventory"
          element={
            <RequireRole roles={['clinic_admin', 'front_desk']}>
              <InventoryDashboard />
            </RequireRole>
          }
        />
        <Route
          path="/inventory/items"
          element={
            <RequireRole roles={['clinic_admin', 'front_desk']}>
              <InventoryItems />
            </RequireRole>
          }
        />
        <Route
          path="/inventory/suppliers"
          element={
            <RequireRole roles={['clinic_admin', 'front_desk']}>
              <InventorySuppliers />
            </RequireRole>
          }
        />
        <Route
          path="/inventory/purchase-orders"
          element={
            <RequireRole roles={['clinic_admin', 'front_desk']}>
              <InventoryPurchaseOrders />
            </RequireRole>
          }
        />
        <Route
          path="/inventory/assets"
          element={
            <RequireRole roles={['clinic_admin', 'front_desk']}>
              <InventoryAssets />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
