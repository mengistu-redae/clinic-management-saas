import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar.jsx';
import Topbar from './Topbar.jsx';

const COLLAPSE_KEY = 'clinicops.sidebarCollapsed';

function readCollapsed() {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * Sidebar + slim topbar shell (modern-UI redesign), replacing the phase-A
 * single top-bar/horizontal-nav shape. `collapsed` (icon-only sidebar) is a
 * plain per-viewer localStorage preference, same pattern ThemeProvider/
 * lib/timezone.js already use rather than a new context - nothing else
 * needs to read it. `mobileOpen` is pure in-memory UI state (never
 * persisted - a reopened app should start with the drawer closed).
 */
export default function AppShell() {
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const [mobileOpen, setMobileOpen] = useState(false);

  function toggleCollapsed() {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0');
      } catch {
        // best-effort only, same as every other localStorage-backed preference here
      }
      return next;
    });
  }

  return (
    <div className="flex min-h-screen bg-slate-50">
      <Sidebar collapsed={collapsed} mobileOpen={mobileOpen} onCloseMobile={() => setMobileOpen(false)} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar
          onOpenMobileMenu={() => setMobileOpen(true)}
          collapsed={collapsed}
          onToggleCollapsed={toggleCollapsed}
        />
        <main className="flex-1 px-4 py-6 sm:px-6 sm:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
