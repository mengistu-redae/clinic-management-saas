import { useQuery } from '@tanstack/react-query';
import { apiGet } from './client.js';

// ---- auth ----

export function useAuthMe() {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      try {
        return await apiGet('/auth/me');
      } catch (err) {
        // /auth/me itself returning 401 is an expected "not logged in"
        // state, not an error to retry or redirect on - every other /api
        // call redirects on 401 (see api/client.js), this one is the
        // exception since it's how the app finds out it's logged out.
        if (err.status === 401) {
          return { authenticated: false };
        }
        throw err;
      }
    },
    retry: false,
    staleTime: 5 * 60 * 1000,
  });
}

// ---- clinic (phase 1 wiring check - see spring-boot-api's ClinicController) ----

export function useMyClinic(enabled) {
  return useQuery({
    queryKey: ['clinic', 'me'],
    queryFn: () => apiGet('/api/clinic/me'),
    enabled,
  });
}
