import { useAuth } from './auth/AuthContext.jsx';
import AppShell from './layout/AppShell.jsx';
import PublicShell from './layout/PublicShell.jsx';

/**
 * Phase-1 placeholder: a single root route that renders the signed-in shell
 * or the public landing page depending on auth state. Real per-role routing
 * (patient portal, front-desk, provider, clinic-admin, platform-admin) is
 * built out phase by phase starting in phase 2 - see CLAUDE.md.
 */
export default function App() {
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
