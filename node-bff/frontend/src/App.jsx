import { Routes, Route, Link } from 'react-router-dom';
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

/** Logged-out landing at "/" - PublicShell's own header/nav carries the wayfinding (Book/Track/Log in). */
function PublicHome() {
  return (
    <div className="flex flex-col items-center gap-4 py-16 text-center">
      <h1 className="text-2xl font-semibold text-ink">Clinic Management</h1>
      <p className="max-w-sm text-sm text-ink-muted">
        Book an appointment as a guest, or sign in as clinic staff or a patient to continue.
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
  return (
    <div className="py-16 text-center">
      <p className="text-xl font-semibold text-ink">Page not found</p>
      <Link to="/" className="mt-2 inline-block text-sm text-brand hover:underline">
        Back home
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
        {/* Booking flow - public (guest) and patient alike, same as the
            reference's /search, /trips/:id, /bookings/:id, /track-booking. */}
        <Route path="/book" element={<ClinicPicker />} />
        <Route path="/book/:clinicId" element={<BookingForm />} />
        <Route path="/appointments/:id" element={<AppointmentDetail />} />
        <Route path="/track-appointment" element={<TrackAppointment />} />
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
        {/* Front-desk workflow - staff-only, no guest/public angle. */}
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
            <RequireRole role="front_desk">
              <FrontDeskAppointments />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/appointments/:id"
          element={
            <RequireRole role="front_desk">
              <FrontDeskAppointmentDetail />
            </RequireRole>
          }
        />
        <Route
          path="/front-desk/appointments/:id/reschedule"
          element={
            <RequireRole role="front_desk">
              <FrontDeskReschedule />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
