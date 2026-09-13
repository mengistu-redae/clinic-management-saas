import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiGet, apiPost } from './client.js';

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

/** Single-appointment ownership-scoped read - see AppointmentController.myAppointment. */
export function useMyAppointment(id) {
  return useQuery({
    queryKey: ['my-appointment', id],
    queryFn: () => apiGet(`/api/my-appointments/${id}`),
    enabled: Boolean(id),
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

// ---- booking flow (public - reachable logged-out too, see ClinicController/
// AppointmentTypeController/ProviderController/AvailabilityController) ----

export function useClinicsDirectory() {
  return useQuery({
    queryKey: ['clinics'],
    queryFn: () => apiGet('/api/clinics'),
    staleTime: 5 * 60 * 1000,
  });
}

export function useClinicAppointmentTypes(clinicId) {
  return useQuery({
    queryKey: ['clinics', clinicId, 'appointment-types'],
    queryFn: () => apiGet(`/api/clinics/${clinicId}/appointment-types`),
    enabled: Boolean(clinicId),
    staleTime: 5 * 60 * 1000,
  });
}

export function useClinicProviders(clinicId) {
  return useQuery({
    queryKey: ['clinics', clinicId, 'providers'],
    queryFn: () => apiGet(`/api/clinics/${clinicId}/providers`),
    enabled: Boolean(clinicId),
    staleTime: 5 * 60 * 1000,
  });
}

/** Open slots for one provider+appointment-type combo - see AvailabilityController (providerId/appointmentTypeId both required server-side). */
export function useAvailability(clinicId, providerId, appointmentTypeId) {
  return useQuery({
    queryKey: ['clinics', clinicId, 'availability', providerId, appointmentTypeId],
    queryFn: () => apiGet(`/api/clinics/${clinicId}/availability?providerId=${providerId}&appointmentTypeId=${appointmentTypeId}`),
    enabled: Boolean(clinicId && providerId && appointmentTypeId),
  });
}

/** Two-factor public tracking, driven by a submitted {ref, phone} rather than fetching on every keystroke - see pages/TrackAppointment.jsx. */
export function useTrackAppointment(ref, phone) {
  return useQuery({
    queryKey: ['track-appointment', ref, phone],
    queryFn: () => apiGet(`/api/appointments/track/${encodeURIComponent(ref)}?phone=${encodeURIComponent(phone)}`),
    enabled: Boolean(ref && phone),
    retry: false,
  });
}

function invalidateMyAppointments(queryClient, id) {
  queryClient.invalidateQueries({ queryKey: ['my-appointments'] });
  if (id) {
    queryClient.invalidateQueries({ queryKey: ['my-appointment', id] });
  }
}

/**
 * patient_portal OR front_desk channel - which one is decided server-side
 * from the caller's JWT role, never client-supplied. patient_portal never
 * sends patientId (the caller's own patient record is resolved server
 * -side); front_desk always does (an existing, tenant-checked Patient).
 */
export function useCreateAppointment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/appointments', body),
    onSuccess: () => {
      invalidateMyAppointments(queryClient);
      queryClient.invalidateQueries({ queryKey: ['appointments'] });
    },
  });
}

/** guest channel - no session, no JWT; contactName/contactPhone identify the booking instead of an account. */
export function useCreateGuestAppointment() {
  return useMutation({
    mutationFn: (body) => apiPost('/api/appointments/guest', body),
  });
}

export function useCancelMyAppointment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason) => apiPost(`/api/my-appointments/${id}/cancel`, reason ? { reason } : undefined),
    onSuccess: () => invalidateMyAppointments(queryClient, id),
  });
}

export function useRescheduleMyAppointment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/my-appointments/${id}/reschedule`, body),
    onSuccess: () => invalidateMyAppointments(queryClient, id),
  });
}

// ---- front-desk workflow (staff-scoped - see PatientController/
// AppointmentController/CheckInController/CancellationController/
// RescheduleController/payment.AppointmentPaymentController) ----

export function usePatients(query) {
  return useQuery({
    queryKey: ['patients', query ?? 'all'],
    queryFn: () => apiGet(`/api/patients${query ? `?query=${encodeURIComponent(query)}` : ''}`),
  });
}

export function usePatient(id) {
  return useQuery({
    queryKey: ['patient', id],
    queryFn: () => apiGet(`/api/patients/${id}`),
    enabled: Boolean(id),
  });
}

export function useCreatePatient() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/patients', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['patients'] }),
  });
}

/** Tenant-scoped single-appointment read (staff) - distinct from the patient-owned useMyAppointment. */
export function useAppointment(id) {
  return useQuery({
    queryKey: ['appointment', id],
    queryFn: () => apiGet(`/api/appointments/${id}`),
    enabled: Boolean(id),
  });
}

function invalidateAppointment(queryClient, id) {
  queryClient.invalidateQueries({ queryKey: ['appointments'] });
  queryClient.invalidateQueries({ queryKey: ['appointment', id] });
}

function useCheckInAction(path) {
  return function useAction(id) {
    const queryClient = useQueryClient();
    return useMutation({
      mutationFn: (body) => apiPost(`/api/appointments/${id}/${path}`, body),
      onSuccess: () => invalidateAppointment(queryClient, id),
    });
  };
}

/** Each mirrors CheckInController's own linear sequence - booked -> checked_in -> roomed -> with_provider -> checked_out, plus no_show (only from booked). */
export const useCheckIn = useCheckInAction('check-in');
export const useRoom = useCheckInAction('room');
export const useStart = useCheckInAction('start');
export const useCheckOut = useCheckInAction('check-out');
export const useMarkNoShow = useCheckInAction('no-show');

export function useCancelAppointment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason) => apiPost(`/api/appointments/${id}/cancel`, reason ? { reason } : undefined),
    onSuccess: () => invalidateAppointment(queryClient, id),
  });
}

export function useRescheduleAppointment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointments/${id}/reschedule`, body),
    onSuccess: () => invalidateAppointment(queryClient, id),
  });
}

export function useAppointmentPayments(id) {
  return useQuery({
    queryKey: ['appointment-payments', id],
    queryFn: () => apiGet(`/api/appointments/${id}/payments`),
    enabled: Boolean(id),
  });
}

export function useCreateAppointmentPayment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointments/${id}/payments`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['appointment-payments', id] }),
  });
}
