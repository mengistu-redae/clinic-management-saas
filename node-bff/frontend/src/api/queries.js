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

/** Staff branding - see clinicsettings.ClinicBrandingController. Consumed by theme/BrandingProvider.jsx. */
export function useClinicBranding(enabled) {
  return useQuery({
    queryKey: ['clinic', 'branding'],
    queryFn: () => apiGet('/api/clinic/branding'),
    enabled,
    staleTime: 5 * 60 * 1000,
  });
}

// ---- patient dashboard ----

export function useMyAppointments(enabled) {
  return useQuery({
    queryKey: ['my-appointments'],
    queryFn: () => apiGet('/api/my-appointments'),
    enabled,
  });
}

export function useMyLabOrders(enabled) {
  return useQuery({
    queryKey: ['my-lab-orders'],
    queryFn: () => apiGet('/api/my-lab-orders'),
    enabled,
  });
}

// ---- provider dashboard ----

/** Today's schedule - already day-scoped server-side (ZoneOffset.UTC), see AppointmentController.mySchedule. */
export function useMySchedule(enabled) {
  return useQuery({
    queryKey: ['my-schedule'],
    queryFn: () => apiGet('/api/my-schedule'),
    enabled,
  });
}

// ---- front-desk / clinic-admin dashboards ----

/** Tenant-wide appointment list (front_desk/clinic_admin/provider) - see AppointmentController.appointments. */
export function useAppointments(enabled) {
  return useQuery({
    queryKey: ['appointments'],
    queryFn: () => apiGet('/api/appointments'),
    enabled,
  });
}

export function useProviders(enabled, status) {
  return useQuery({
    queryKey: ['providers', status ?? 'all'],
    queryFn: () => apiGet(`/api/providers${status ? `?status=${status}` : ''}`),
    enabled,
  });
}

export function useRooms(enabled, status) {
  return useQuery({
    queryKey: ['rooms', status ?? 'all'],
    queryFn: () => apiGet(`/api/rooms${status ? `?status=${status}` : ''}`),
    enabled,
  });
}

export function useAppointmentTypes(enabled, status) {
  return useQuery({
    queryKey: ['appointment-types', status ?? 'all'],
    queryFn: () => apiGet(`/api/appointment-types${status ? `?status=${status}` : ''}`),
    enabled,
  });
}

/** Pending patient-initiated lab requests awaiting staff confirm-and-order - see PatientLabRequestController. */
export function useLabOrderRequests(enabled) {
  return useQuery({
    queryKey: ['lab-order-requests'],
    queryFn: () => apiGet('/api/lab-orders/requests'),
    enabled,
  });
}

// ---- platform-admin dashboard ----

export function usePlatformClinics(enabled) {
  return useQuery({
    queryKey: ['platform', 'clinics'],
    queryFn: () => apiGet('/api/platform/clinics'),
    enabled,
  });
}
