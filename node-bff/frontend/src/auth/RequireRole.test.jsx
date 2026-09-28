import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import RequireRole from './RequireRole.jsx';
import { useAuth } from './AuthContext.jsx';

// RequireRole is a UX-only gate (real authorization is server-side, see its
// own module comment) - mocked useAuth here so each case can drive
// isLoading/authenticated/hasRole directly, without going through a real
// AuthProvider + GET /auth/me fetch.
vi.mock('./AuthContext.jsx', () => ({ useAuth: vi.fn() }));

function renderAt(path, roleProps) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<div>Home page</div>} />
        <Route
          path="/protected"
          element={
            <RequireRole {...roleProps}>
              <div>Protected content</div>
            </RequireRole>
          }
        />
      </Routes>
    </MemoryRouter>
  );
}

describe('RequireRole', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders nothing while auth is still loading, not children or a redirect', () => {
    useAuth.mockReturnValue({ isLoading: true, authenticated: false, hasRole: () => false });
    renderAt('/protected', { role: 'provider' });

    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
    expect(screen.queryByText('Home page')).not.toBeInTheDocument();
  });

  it('redirects an unauthenticated visitor to /auth/login (a real page navigation, not a client route)', () => {
    useAuth.mockReturnValue({ isLoading: false, authenticated: false, hasRole: () => false });
    const originalLocation = window.location;
    delete window.location;
    window.location = { href: '' };

    renderAt('/protected', { role: 'provider' });

    expect(window.location.href).toBe('/auth/login');
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();

    window.location = originalLocation;
  });

  it('redirects to / (client-side) when authenticated but missing the required single role', () => {
    useAuth.mockReturnValue({ isLoading: false, authenticated: true, hasRole: (r) => r === 'front_desk' });
    renderAt('/protected', { role: 'provider' });

    expect(screen.getByText('Home page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders children when authenticated and hasRole matches the single required role', () => {
    useAuth.mockReturnValue({ isLoading: false, authenticated: true, hasRole: (r) => r === 'provider' });
    renderAt('/protected', { role: 'provider' });

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('with a roles array, allows access if ANY of the given roles matches (e.g. clinic_admin override)', () => {
    useAuth.mockReturnValue({ isLoading: false, authenticated: true, hasRole: (r) => r === 'clinic_admin' });
    renderAt('/protected', { roles: ['front_desk', 'clinic_admin'] });

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('with a roles array, redirects when none of the given roles match', () => {
    useAuth.mockReturnValue({ isLoading: false, authenticated: true, hasRole: (r) => r === 'patient' });
    renderAt('/protected', { roles: ['front_desk', 'clinic_admin'] });

    expect(screen.getByText('Home page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });
});
