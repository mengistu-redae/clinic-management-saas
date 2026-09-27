import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiGet, apiPost, apiPostForm } from './client.js';

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

/** Richer worklist counterpart of useAppointments - real startTime + guest contactName, see AppointmentController.appointmentsWorklist. */
export function useAppointmentsWorklist(enabled) {
  return useQuery({
    queryKey: ['appointments-worklist'],
    queryFn: () => apiGet('/api/appointments/worklist'),
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

/** Clinic-admin dashboard analytics (frontend phase O) - see ClinicAnalyticsController. `days` defaults server-side to 30 when omitted. */
export function useClinicAnalytics(enabled, days) {
  return useQuery({
    queryKey: ['clinic-analytics', days],
    queryFn: () => apiGet(`/api/clinic/analytics${days ? `?days=${days}` : ''}`),
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

// ---- refunds (owner-agnostic, addressed by the payment's own id - see
// payment.PaymentController, phase 16) ----

export function usePaymentRefunds(paymentId) {
  return useQuery({
    queryKey: ['payment-refunds', paymentId],
    queryFn: () => apiGet(`/api/payments/${paymentId}/refunds`),
    enabled: Boolean(paymentId),
  });
}

/** Invalidates both this payment's own refund list and every payments-list query, since a refund also flips the parent payment's own gatewayStatus (shown inline in PaymentsPanel). */
export function useCreateRefund(paymentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/payments/${paymentId}/refund`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['payment-refunds', paymentId] });
      queryClient.invalidateQueries({ queryKey: ['appointment-payments'] });
      queryClient.invalidateQueries({ queryKey: ['lab-order-payments'] });
    },
  });
}

// ---- invoices (see AppointmentInvoiceController/LabOrderInvoiceController - phase 15/frontend phase M) ----

export function useAppointmentInvoice(id) {
  return useQuery({
    queryKey: ['appointment-invoice', id],
    queryFn: () => apiGet(`/api/appointments/${id}/invoice`),
    enabled: Boolean(id),
    retry: false,
  });
}

export function useGenerateAppointmentInvoice(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/appointments/${id}/invoice`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['appointment-invoice', id] }),
  });
}

// ---- clinic-admin config (providers/rooms/appointment-types/fee-policies/
// working-hours/lab-rates/settings/branding - see ProviderController/
// RoomController/AppointmentTypeController/FeePolicyController/
// ProviderWorkingHoursController/LabRateController/ClinicSettingsController/
// ClinicBrandingController) ----

export function useCreateProvider() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/providers', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

/** Partial update, per ProviderController's own /update shape - only non-null fields are applied server-side. */
export function useUpdateProvider(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/providers/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

export function useLinkProviderLogin(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (email) => apiPost(`/api/providers/${id}/link-login`, { email }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

export function useUnlinkProviderLogin(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/providers/${id}/unlink-login`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

/** Multipart upload (frontend phase K) - file is a raw File/Blob from an <input type="file">, wrapped in FormData under the "file" field ProviderController.uploadSignature expects. */
export function useUploadProviderSignature(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file) => {
      const formData = new FormData();
      formData.append('file', file);
      return apiPostForm(`/api/providers/${id}/signature`, formData);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

export function useRemoveProviderSignature(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/providers/${id}/signature/remove`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['providers'] }),
  });
}

export function useProviderWorkingHours(providerId) {
  return useQuery({
    queryKey: ['provider-working-hours', providerId],
    queryFn: () => apiGet(`/api/providers/${providerId}/working-hours`),
    enabled: Boolean(providerId),
  });
}

export function useCreateWorkingHours(providerId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/providers/${providerId}/working-hours`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['provider-working-hours', providerId] }),
  });
}

export function useRemoveWorkingHours(providerId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id) => apiPost(`/api/providers/${providerId}/working-hours/${id}/remove`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['provider-working-hours', providerId] }),
  });
}

export function useCreateRoom() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/rooms', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rooms'] }),
  });
}

export function useUpdateRoom(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/rooms/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rooms'] }),
  });
}

export function useCreateAppointmentType() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/appointment-types', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['appointment-types'] }),
  });
}

export function useUpdateAppointmentType(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointment-types/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['appointment-types'] }),
  });
}

/** Unfiltered (no providerId) fetches every tier, clinic-wide defaults and every provider override alike - the admin UI groups them client-side, same shape as the reference project's own RefundPolicies.jsx. */
export function useFeePolicies() {
  return useQuery({
    queryKey: ['fee-policies'],
    queryFn: () => apiGet('/api/fee-policies'),
  });
}

function invalidateFeePolicies(queryClient) {
  queryClient.invalidateQueries({ queryKey: ['fee-policies'] });
}

export function useCreateFeePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/fee-policies', body),
    onSuccess: () => invalidateFeePolicies(queryClient),
  });
}

export function useUpdateFeePolicy(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/fee-policies/${id}/update`, body),
    onSuccess: () => invalidateFeePolicies(queryClient),
  });
}

export function useDeleteFeePolicy(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/fee-policies/${id}/delete`),
    onSuccess: () => invalidateFeePolicies(queryClient),
  });
}

/** {overrides, effective, defaults} - see ClinicSettingsResponse. */
export function useClinicSettings(enabled) {
  return useQuery({
    queryKey: ['clinic', 'settings'],
    queryFn: () => apiGet('/api/clinic/settings'),
    enabled,
  });
}

/** Full-replace, per ClinicSettingsController - a field left out (null) reverts that column to the platform default. */
export function useUpdateClinicSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/clinic/settings', body),
    onSuccess: (data) => queryClient.setQueryData(['clinic', 'settings'], data),
  });
}

/** Full-replace of the disjoint branding column group - see ClinicBrandingController. Also refreshes the read query BrandingProvider/AppShell consume, so a saved change themes the workspace on next fetch. */
export function useUpdateClinicBranding() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/clinic/branding', body),
    onSuccess: (data) => queryClient.setQueryData(['clinic', 'branding'], data),
  });
}

export function useLabRates(enabled) {
  return useQuery({
    queryKey: ['clinic', 'lab-rates'],
    queryFn: () => apiGet('/api/clinic/lab-rates'),
    enabled,
  });
}

function invalidateLabRates(queryClient) {
  queryClient.invalidateQueries({ queryKey: ['clinic', 'lab-rates'] });
}

export function useCreateLabRate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/clinic/lab-rates', body),
    onSuccess: () => invalidateLabRates(queryClient),
  });
}

export function useUpdateLabRate(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/clinic/lab-rates/${id}/update`, body),
    onSuccess: () => invalidateLabRates(queryClient),
  });
}

export function useDeleteLabRate(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/clinic/lab-rates/${id}/delete`),
    onSuccess: () => invalidateLabRates(queryClient),
  });
}

// ---- patient chart: allergies/vitals/medical history/consent (phase
// 8/9/10 backend, frontend phases H/I) - shared by
// front-desk/AppointmentDetail.jsx and provider/Encounter.jsx via
// components/PatientChart.jsx ----

export function useAllergies(patientId) {
  return useQuery({
    queryKey: ['allergies', patientId],
    queryFn: () => apiGet(`/api/patients/${patientId}/allergies`),
    enabled: Boolean(patientId),
  });
}

export function useCreateAllergy(patientId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/patients/${patientId}/allergies`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['allergies', patientId] }),
  });
}

/** Partial update - reactionType/severity/status only, per AllergyController.updateAllergy. */
export function useUpdateAllergy(patientId, id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/patients/${patientId}/allergies/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['allergies', patientId] }),
  });
}

/** 404 (nothing recorded yet) is an expected, common state here, same reasoning as useEncounter - retry:false so it surfaces immediately instead of retrying a few times first. */
export function useVitals(appointmentId) {
  return useQuery({
    queryKey: ['vitals', appointmentId],
    queryFn: () => apiGet(`/api/appointments/${appointmentId}/vitals`),
    enabled: Boolean(appointmentId),
    retry: false,
  });
}

/** Full-replace on every call, per UpsertVitalsRequest. */
export function useUpsertVitals(appointmentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointments/${appointmentId}/vitals`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['vitals', appointmentId] }),
  });
}

/** 404 (nothing recorded yet) is expected - same reasoning as useVitals/useEncounter. */
export function useMedicalHistory(patientId) {
  return useQuery({
    queryKey: ['medical-history', patientId],
    queryFn: () => apiGet(`/api/patients/${patientId}/medical-history`),
    enabled: Boolean(patientId),
    retry: false,
  });
}

/** Full-replace on every call, per UpsertMedicalHistoryRequest - omitted fields clear rather than persist. */
export function useUpsertMedicalHistory(patientId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/patients/${patientId}/medical-history`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['medical-history', patientId] }),
  });
}

/** Accumulates - never a singleton, unlike allergies/vitals/medical history. See ConsentRecord's own javadoc: genuinely immutable, no update/delete anywhere. */
export function useConsentRecords(patientId) {
  return useQuery({
    queryKey: ['consent-records', patientId],
    queryFn: () => apiGet(`/api/patients/${patientId}/consent-records`),
    enabled: Boolean(patientId),
  });
}

export function useCreateConsentRecord(patientId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/patients/${patientId}/consent-records`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['consent-records', patientId] }),
  });
}

// ---- provider clinical (encounter documentation + prescriptions - see
// EncounterController) ----

/**
 * 404 (no encounter documented yet) is an expected, common state here, not
 * a failure - retry:false so a real 404 surfaces immediately as
 * isError/error.status instead of retrying a few times first; the page
 * itself treats a 404 as "show a blank create form," not an error banner.
 */
export function useEncounter(appointmentId) {
  return useQuery({
    queryKey: ['encounter', appointmentId],
    queryFn: () => apiGet(`/api/appointments/${appointmentId}/encounter`),
    enabled: Boolean(appointmentId),
    retry: false,
  });
}

export function useUpsertEncounter(appointmentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointments/${appointmentId}/encounter`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['encounter', appointmentId] }),
  });
}

/** Full-replace - the given list becomes the entire prescription list, never merged with what was there before. */
export function useReplacePrescriptions(appointmentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (items) => apiPost(`/api/appointments/${appointmentId}/encounter/prescriptions`, items),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['encounter', appointmentId] }),
  });
}

/** No body - a pure state transition, idempotent re-call (see EncounterService.sign). Locks the encounter/prescriptions once it succeeds - frontend phase J. */
export function useSignEncounter(appointmentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/appointments/${appointmentId}/encounter/sign`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['encounter', appointmentId] }),
  });
}

/** Only reachable once the encounter is signed (see EncounterService.addAddendum) - frontend phase J. */
export function useAddAddendum(appointmentId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/appointments/${appointmentId}/encounter/addenda`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['encounter', appointmentId] }),
  });
}

// ---- lab orders (staff - provider/clinic_admin; see LabOrderController/
// LabOrderStatusController/LabOrderCancellationController/
// PatientLabRequestController/payment.LabOrderPaymentController) ----

export function useLabOrders(enabled) {
  return useQuery({
    queryKey: ['lab-orders'],
    queryFn: () => apiGet('/api/lab-orders'),
    enabled,
  });
}

export function useLabOrder(id) {
  return useQuery({
    queryKey: ['lab-order', id],
    queryFn: () => apiGet(`/api/lab-orders/${id}`),
    enabled: Boolean(id),
  });
}

export function useCreateLabOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/lab-orders', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['lab-orders'] }),
  });
}

function invalidateLabOrder(queryClient, id) {
  queryClient.invalidateQueries({ queryKey: ['lab-orders'] });
  queryClient.invalidateQueries({ queryKey: ['lab-order', id] });
  queryClient.invalidateQueries({ queryKey: ['lab-order-requests'] });
}

/** Partial update - clinical fields only while status = "ordered", per LabOrderService.update. */
export function useUpdateLabOrder(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/lab-orders/${id}/update`, body),
    onSuccess: () => invalidateLabOrder(queryClient, id),
  });
}

function useLabOrderAction(path) {
  return function useAction(id) {
    const queryClient = useQueryClient();
    return useMutation({
      mutationFn: (body) => apiPost(`/api/lab-orders/${id}/${path}`, body),
      onSuccess: () => invalidateLabOrder(queryClient, id),
    });
  };
}

/** Each mirrors LabOrderStatusService's own linear sequence - ordered -> specimen_collected -> in_transit -> resulted -> reviewed. */
export const useCollectSpecimen = useLabOrderAction('collect-specimen');
export const useSendLabOrder = useLabOrderAction('send');
export const useResultLabOrder = useLabOrderAction('result');
export const useReviewLabOrder = useLabOrderAction('review');
export const useCancelLabOrder = useLabOrderAction('cancel');
/** Turns a "requested" (patient-initiated) order into a priced "ordered" one - see PatientLabRequestController.confirmAndOrder. */
export const useConfirmAndOrder = useLabOrderAction('confirm-and-order');

export function useLabOrderPayments(id) {
  return useQuery({
    queryKey: ['lab-order-payments', id],
    queryFn: () => apiGet(`/api/lab-orders/${id}/payments`),
    enabled: Boolean(id),
  });
}

export function useCreateLabOrderPayment(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/lab-orders/${id}/payments`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['lab-order-payments', id] }),
  });
}

export function useLabOrderInvoice(id) {
  return useQuery({
    queryKey: ['lab-order-invoice', id],
    queryFn: () => apiGet(`/api/lab-orders/${id}/invoice`),
    enabled: Boolean(id),
    retry: false,
  });
}

export function useGenerateLabOrderInvoice(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiPost(`/api/lab-orders/${id}/invoice`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['lab-order-invoice', id] }),
  });
}

// ---- patient lab requests (see PatientLabRequestController.createRequest - GET is useMyLabOrders above) ----

export function useCreateLabRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/my-lab-orders', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['my-lab-orders'] }),
  });
}

/** Two-factor public tracking, driven by a submitted {ref, phone} - see pages/TrackLabOrder.jsx. */
export function useTrackLabOrder(ref, phone) {
  return useQuery({
    queryKey: ['track-lab-order', ref, phone],
    queryFn: () => apiGet(`/api/lab-orders/track/${encodeURIComponent(ref)}?phone=${encodeURIComponent(phone)}`),
    enabled: Boolean(ref && phone),
    retry: false,
  });
}

// ---- referrals (staff - provider/clinic_admin; see ReferralController, frontend phase L) ----

export function useReferrals(enabled) {
  return useQuery({
    queryKey: ['referrals'],
    queryFn: () => apiGet('/api/referrals'),
    enabled,
  });
}

export function useCreateReferral() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/referrals', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals'] }),
  });
}

/** Partial update - status/priority/notes/clinicalSummary only, per UpdateReferralRequest. */
export function useUpdateReferral(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/referrals/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['referrals'] }),
  });
}

// ---- platform-admin clinic onboarding (see PlatformController) ----

function invalidatePlatformClinics(queryClient) {
  queryClient.invalidateQueries({ queryKey: ['platform', 'clinics'] });
}

/** The only create call in this app that reaches out to Keycloak (a real Organization) before touching Postgres - see ClinicProvisioningService. Slower, more ways to fail (a taken orgAlias, Keycloak unreachable) than every other create form here. */
export function useCreateClinic() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/platform/clinics', body),
    onSuccess: () => invalidatePlatformClinics(queryClient),
  });
}

/** Partial update - name only, per UpdateClinicRequest (keycloak_org_id is fixed at creation). */
export function useUpdateClinic(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/platform/clinics/${id}/update`, body),
    onSuccess: () => invalidatePlatformClinics(queryClient),
  });
}

function useClinicStatusAction(path) {
  return function useAction(id) {
    const queryClient = useQueryClient();
    return useMutation({
      mutationFn: () => apiPost(`/api/platform/clinics/${id}/${path}`),
      onSuccess: () => invalidatePlatformClinics(queryClient),
    });
  };
}

export const useDeactivateClinic = useClinicStatusAction('deactivate');
export const useReactivateClinic = useClinicStatusAction('reactivate');

// ---- pharmacy (phase 20 - see MedicationController/DispenseController) ----

export function useMedications(enabled, status) {
  return useQuery({
    queryKey: ['medications', status ?? 'all'],
    queryFn: () => apiGet(`/api/clinic/medications${status ? `?status=${status}` : ''}`),
    enabled,
  });
}

export function useCreateMedication() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost('/api/clinic/medications', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['medications'] }),
  });
}

export function useUpdateMedication(id) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/clinic/medications/${id}/update`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['medications'] }),
  });
}

export function useStockBatches(medicationId) {
  return useQuery({
    queryKey: ['stock-batches', medicationId],
    queryFn: () => apiGet(`/api/clinic/medications/${medicationId}/stock-batches`),
    enabled: Boolean(medicationId),
  });
}

export function useReceiveStockBatch(medicationId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/clinic/medications/${medicationId}/stock-batches`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['stock-batches', medicationId] }),
  });
}

export function useWriteOffStockBatch(medicationId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ batchId, ...body }) => apiPost(`/api/clinic/medications/${medicationId}/stock-batches/${batchId}/write-off`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['stock-batches', medicationId] }),
  });
}

/** GET /api/pharmacy/queue - active prescriptions not yet fully dispensed. */
export function usePharmacyQueue(enabled) {
  return useQuery({
    queryKey: ['pharmacy', 'queue'],
    queryFn: () => apiGet('/api/pharmacy/queue'),
    enabled,
  });
}

export function useDispensePrescription(prescriptionId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body) => apiPost(`/api/prescriptions/${prescriptionId}/dispense`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pharmacy', 'queue'] });
      // Prefix match - invalidates every medication's own stock-batches query, not just one, since a dispense's quantityOnHand change is only known after the fact.
      queryClient.invalidateQueries({ queryKey: ['stock-batches'] });
    },
  });
}
