import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useAppointment,
  usePatient,
  useProviders,
  useAppointmentTypes,
  useCheckIn,
  useRoom,
  useStart,
  useCheckOut,
  useMarkNoShow,
  useCancelAppointment,
  useAppointmentPayments,
  useCreateAppointmentPayment,
  useAppointmentInvoice,
  useGenerateAppointmentInvoice,
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import PatientChart from '../../components/PatientChart.jsx';
import InvoicePanel from '../../components/InvoicePanel.jsx';
import PaymentsPanel from '../../components/PaymentsPanel.jsx';
import { formatDateTime } from '../../lib/format.js';

const TERMINAL_STATUSES = new Set(['cancelled', 'checked_out', 'no_show']);
// Mirrors EncounterService's own status gate (see provider/Encounter.jsx) -
// only clinic_admin sees this link at all (front_desk has zero backend
// access to EncounterController, by design - clinical content isn't a
// front-desk concern), and only once there's actually something to chart.
const DOCUMENTABLE_STATUSES = new Set(['with_provider', 'checked_out']);

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Staff view of one appointment (GET /api/appointments/{id}, tenant
 * -scoped) - the front-desk counterpart to the patient's own
 * AppointmentDetail.jsx, plus what a patient's own view has no business
 * doing: driving the check-in state machine and recording a payment.
 * Mirrors the reference project's AgentBookingDetail.jsx shape (payments
 * section, cancel-with-confirm) - its check-in is a flat flag though, so
 * the actual next-action sequence here is designed fresh to match
 * CheckInController exactly.
 */
export default function AppointmentDetail() {
  const { t } = useTranslation();
  const { id } = useParams();
  const { hasRole } = useAuth();
  const appointmentQuery = useAppointment(id);
  const appointment = appointmentQuery.data;

  const patientQuery = usePatient(appointment?.patientId);
  const providersQuery = useProviders(true);
  const typesQuery = useAppointmentTypes(true);

  const checkIn = useCheckIn(id);
  const room = useRoom(id);
  const start = useStart(id);
  const checkOut = useCheckOut(id);
  const markNoShow = useMarkNoShow(id);
  const cancelAppointment = useCancelAppointment(id);

  const [presentedId, setPresentedId] = useState('');
  const [actionError, setActionError] = useState(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelError, setCancelError] = useState(null);

  const paymentsQuery = useAppointmentPayments(id);
  const createPayment = useCreateAppointmentPayment(id);
  const invoiceQuery = useAppointmentInvoice(id);
  const generateInvoice = useGenerateAppointmentInvoice(id);

  async function runAction(mutation, body) {
    setActionError(null);
    try {
      await mutation.mutateAsync(body);
    } catch (err) {
      setActionError(err.message || t('fdAppointmentDetail.errorAction'));
    }
  }

  async function handleCancel() {
    setCancelError(null);
    try {
      await cancelAppointment.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setCancelError(err.message || t('appointmentDetail.alreadyCancelled'));
      } else {
        setCancelError(err.message || t('appointmentDetail.errorCancel'));
      }
      setConfirmingCancel(false);
    }
  }

  if (appointmentQuery.isLoading) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (appointmentQuery.isError) {
    return <ErrorBanner message={appointmentQuery.error?.message} onRetry={appointmentQuery.refetch} />;
  }

  const providerName = providersQuery.data?.find((p) => p.id === appointment.providerId)?.fullName;
  const typeName = typesQuery.data?.find((type) => type.id === appointment.appointmentTypeId)?.name;
  const patientName = appointment.patientId
    ? patientQuery.data
      ? `${patientQuery.data.firstName} ${patientQuery.data.lastName}`
      : '…'
    : appointment.contactName || t('frontDeskAppointments.guest');

  const status = appointment.status;
  const isTerminal = TERMINAL_STATUSES.has(status);

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">{t('appointmentDetail.title')}</h1>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{patientName}</p>
        <p className="text-sm text-ink-muted">
          {typeName || t('appointmentDetail.appointmentFallback')} with {providerName || t('appointmentDetail.providerFallback')}
        </p>
        {appointment.contactPhone && <p className="text-xs text-ink-muted">{appointment.contactPhone}</p>}

        <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
          <div>
            <dt className="text-ink-muted">{t('appointmentDetail.reference')}</dt>
            <dd className="font-mono text-xs text-ink">{appointment.appointmentRef}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('fdAppointmentDetail.channel')}</dt>
            <dd className="text-ink capitalize">{t(`channel.${appointment.channel}`, { defaultValue: appointment.channel.replace(/_/g, ' ') })}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('appointmentDetail.when')}</dt>
            <dd className="text-ink">{appointment.startTime ? formatDateTime(appointment.startTime) : '—'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">{t('common.bookedAt')}</dt>
            <dd className="text-ink">{formatDateTime(appointment.bookedAt)}</dd>
          </div>
        </dl>
      </div>

      <PatientChart patientId={appointment.patientId} appointmentId={id} />

      {hasRole('clinic_admin') && DOCUMENTABLE_STATUSES.has(status) && (
        <div className="mt-5 flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4">
          <p className="text-sm text-ink-muted">{t('fdAppointmentDetail.documentEncounterNote')}</p>
          <Link
            to={`/provider/appointments/${id}/encounter`}
            className="shrink-0 rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark"
          >
            {t('fdAppointmentDetail.documentEncounter')}
          </Link>
        </div>
      )}

      {!isTerminal && (
        <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <p className="mb-3 text-sm font-semibold text-ink">{t('fdAppointmentDetail.nextStep')}</p>
          {status === 'booked' && (
            <div className="flex flex-wrap items-end gap-3">
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
                  {t('fdAppointmentDetail.idPresentedOptional')}
                </span>
                <input value={presentedId} onChange={(e) => setPresentedId(e.target.value)} className={inputClass} />
              </label>
              <button
                type="button"
                disabled={checkIn.isPending}
                onClick={() => runAction(checkIn, presentedId.trim() ? { presentedIdNumber: presentedId.trim() } : undefined)}
                className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
              >
                {checkIn.isPending ? t('fdAppointmentDetail.checkingIn') : t('fdAppointmentDetail.checkIn')}
              </button>
              <button
                type="button"
                disabled={markNoShow.isPending}
                onClick={() => runAction(markNoShow)}
                className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink-muted hover:bg-slate-50"
              >
                {t('fdAppointmentDetail.markNoShow')}
              </button>
            </div>
          )}
          {status === 'checked_in' && (
            <button
              type="button"
              disabled={room.isPending}
              onClick={() => runAction(room)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
            >
              {room.isPending ? t('fdAppointmentDetail.rooming') : t('fdAppointmentDetail.room')}
            </button>
          )}
          {status === 'roomed' && (
            <button
              type="button"
              disabled={start.isPending}
              onClick={() => runAction(start)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
            >
              {start.isPending ? t('fdAppointmentDetail.starting') : t('fdAppointmentDetail.startVisit')}
            </button>
          )}
          {status === 'with_provider' && (
            <button
              type="button"
              disabled={checkOut.isPending}
              onClick={() => runAction(checkOut)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
            >
              {checkOut.isPending ? t('fdAppointmentDetail.checkingOut') : t('fdAppointmentDetail.checkOut')}
            </button>
          )}
          {actionError && (
            <div className="mt-3">
              <ErrorBanner message={actionError} />
            </div>
          )}
        </div>
      )}

      {status !== 'cancelled' && <PaymentsPanel paymentsQuery={paymentsQuery} createPayment={createPayment} />}

      {status !== 'cancelled' && (
        <InvoicePanel invoiceQuery={invoiceQuery} generateInvoice={generateInvoice} pdfUrl={`/api/appointments/${id}/invoice/pdf`} />
      )}

      {cancelError && (
        <div className="mt-4">
          <ErrorBanner message={cancelError} />
        </div>
      )}

      {!isTerminal && (
        <div className="mt-5 flex items-center gap-3">
          <Link
            to={`/front-desk/appointments/${id}/reschedule`}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink hover:bg-slate-50"
          >
            {t('appointmentDetail.reschedule')}
          </Link>
          {!confirmingCancel ? (
            <button
              type="button"
              onClick={() => setConfirmingCancel(true)}
              className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light"
            >
              {t('appointmentDetail.cancelAppointment')}
            </button>
          ) : (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">{t('appointmentDetail.cancelConfirm')}</p>
              <button
                type="button"
                disabled={cancelAppointment.isPending}
                onClick={handleCancel}
                className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50"
              >
                {cancelAppointment.isPending ? t('appointmentDetail.cancelling') : t('appointmentDetail.yesCancel')}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingCancel(false)}
                className="shrink-0 text-sm text-ink-muted hover:underline"
              >
                {t('appointmentDetail.neverMind')}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
