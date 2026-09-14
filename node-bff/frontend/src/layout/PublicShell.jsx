import { Link, Outlet } from 'react-router-dom';

/**
 * Logged-out shell - a header with nav (booking/tracking are both public,
 * reachable with no account at all) plus an Outlet, mirroring AppShell's
 * own header+nav+Outlet shape. Was a dead-end static page with no way to
 * reach any route but /auth/login until this phase - see App.jsx's
 * PublicHome for what renders at "/" itself.
 */
export default function PublicShell() {
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-surface">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-y-2 px-4 py-3 sm:px-6">
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 sm:gap-x-8">
            <Link to="/" className="text-lg font-bold text-brand">
              Clinic Management
            </Link>
            <nav className="flex flex-wrap items-center gap-1">
              <Link to="/book" className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink">
                Book an appointment
              </Link>
              <Link
                to="/track-appointment"
                className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink"
              >
                Track an appointment
              </Link>
              <Link
                to="/track-lab-order"
                className="rounded-lg px-3 py-1.5 text-sm font-medium text-ink-muted hover:bg-slate-100 hover:text-ink"
              >
                Track a lab order
              </Link>
            </nav>
          </div>
          <a
            href="/auth/login"
            className="rounded-lg bg-brand px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-dark"
          >
            Log in
          </a>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <Outlet />
      </main>
    </div>
  );
}
