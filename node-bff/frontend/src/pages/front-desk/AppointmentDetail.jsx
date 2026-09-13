import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
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
} from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import StatusPill from '../../components/StatusPill.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency, formatDateTime } from '../../lib/format.js';

const TERMINAL_STATUSES = new Set(['cancelled', 'checked_out', 'no_show']);

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
  const { id } = useParams();
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
  const [paymentAmount, setPaymentAmount] = useState('');
  const [paymentMethod, setPaymentMethod] = useState('cash');
  const [paymentTxnId, setPaymentTxnId] = useState('');
  const [paymentError, setPaymentError] = useState(null);

  async function runAction(mutation, body) {
    setActionError(null);
    try {
      await mutation.mutateAsync(body);
    } catch (err) {
      setActionError(err.message || 'Could not complete this action. Please try again.');
    }
  }

  async function handleCancel() {
    setCancelError(null);
    try {
      await cancelAppointment.mutateAsync();
      setConfirmingCancel(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setCancelError(err.message || 'This appointment was already cancelled.');
      } else {
        setCancelError(err.message || 'Could not cancel this appointment. Please try again.');
      }
      setConfirmingCancel(false);
    }
  }

  async function handleRecordPayment(event) {
    event.preventDefault();
    setPaymentError(null);
    const amount = Number(paymentAmount);
    if (!paymentAmount || Number.isNaN(amount) || amount <= 0) {
      setPaymentError('Enter a valid amount.');
      return;
    }
    try {
      await createPayment.mutateAsync({ method: paymentMethod, amount, transactionId: paymentTxnId.trim() || undefined });
      setPaymentAmount('');
      setPaymentTxnId('');
    } catch (err) {
      setPaymentError(err.message || 'Could not record this payment. Please try again.');
    }
  }

  if (appointmentQuery.isLoading) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (appointmentQuery.isError) {
    return <ErrorBanner message={appointmentQuery.error?.message} onRetry={appointmentQuery.refetch} />;
  }

  const providerName = providersQuery.data?.find((p) => p.id === appointment.providerId)?.fullName;
  const typeName = typesQuery.data?.find((t) => t.id === appointment.appointmentTypeId)?.name;
  const patientName = appointment.patientId
    ? patientQuery.data
      ? `${patientQuery.data.firstName} ${patientQuery.data.lastName}`
      : '…'
    : appointment.contactName || 'Guest';

  const status = appointment.status;
  const isTerminal = TERMINAL_STATUSES.has(status);
  const payments = paymentsQuery.data || [];

  return (
    <div className="mx-auto max-w-xl">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-ink">Appointment</h1>
        <StatusPill status={status} />
      </div>

      <div className="rounded-xl border border-slate-200 bg-surface p-5">
        <p className="text-lg font-semibold text-ink">{patientName}</p>
        <p className="text-sm text-ink-muted">
          {typeName || 'Appointment'} with {providerName || 'a provider'}
        </p>
        {appointment.contactPhone && <p className="text-xs text-ink-muted">{appointment.contactPhone}</p>}

        <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-slate-100 pt-4 text-sm">
          <div>
            <dt className="text-ink-muted">Reference</dt>
            <dd className="font-mono text-xs text-ink">{appointment.appointmentRef}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Channel</dt>
            <dd className="text-ink capitalize">{appointment.channel.replace(/_/g, ' ')}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">When</dt>
            <dd className="text-ink">{appointment.startTime ? formatDateTime(appointment.startTime) : '—'}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booked</dt>
            <dd className="text-ink">{formatDateTime(appointment.bookedAt)}</dd>
          </div>
        </dl>
      </div>

      {!isTerminal && (
        <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <p className="mb-3 text-sm font-semibold text-ink">Next step</p>
          {status === 'booked' && (
            <div className="flex flex-wrap items-end gap-3">
              <label className="block">
                <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">
                  ID presented (optional)
                </span>
                <input value={presentedId} onChange={(e) => setPresentedId(e.target.value)} className={inputClass} />
              </label>
              <button
                type="button"
                disabled={checkIn.isPending}
                onClick={() => runAction(checkIn, presentedId.trim() ? { presentedIdNumber: presentedId.trim() } : undefined)}
                className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
              >
                {checkIn.isPending ? 'Checking in…' : 'Check in'}
              </button>
              <button
                type="button"
                disabled={markNoShow.isPending}
                onClick={() => runAction(markNoShow)}
                className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-ink-muted hover:bg-slate-50"
              >
                Mark no-show
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
              {room.isPending ? 'Rooming…' : 'Room'}
            </button>
          )}
          {status === 'roomed' && (
            <button
              type="button"
              disabled={start.isPending}
              onClick={() => runAction(start)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
            >
              {start.isPending ? 'Starting…' : 'Start visit'}
            </button>
          )}
          {status === 'with_provider' && (
            <button
              type="button"
              disabled={checkOut.isPending}
              onClick={() => runAction(checkOut)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark disabled:opacity-50"
            >
              {checkOut.isPending ? 'Checking out…' : 'Check out'}
            </button>
          )}
          {actionError && (
            <div className="mt-3">
              <ErrorBanner message={actionError} />
            </div>
          )}
        </div>
      )}

      {status !== 'cancelled' && (
        <div className="mt-5 rounded-xl border border-slate-200 bg-surface p-5">
          <p className="mb-3 text-sm font-semibold text-ink">Payments</p>
          {payments.length > 0 && (
            <ul className="mb-4 flex flex-col gap-1.5 text-sm">
              {payments.map((p) => (
                <li key={p.id} className="flex items-center justify-between text-ink">
                  <span className="capitalize">
                    {p.method}
                    {p.transactionId && <span className="font-mono text-xs text-ink-muted"> ({p.transactionId})</span>}
                  </span>
                  <span className="font-mono">{formatCurrency(p.amount)}</span>
                </li>
              ))}
            </ul>
          )}
          <form onSubmit={handleRecordPayment} className="flex flex-wrap items-end gap-3">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Method</span>
              <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)} className={inputClass}>
                <option value="cash">Cash</option>
                <option value="card">Card</option>
                <option value="mobile_money">Mobile money</option>
                <option value="insurance">Insurance</option>
              </select>
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Amount</span>
              <input
                type="number"
                min="0"
                step="0.01"
                value={paymentAmount}
                onChange={(e) => setPaymentAmount(e.target.value)}
                className={`${inputClass} w-28`}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Txn ID (optional)</span>
              <input value={paymentTxnId} onChange={(e) => setPaymentTxnId(e.target.value)} className={`${inputClass} w-40`} />
            </label>
            <button
              type="submit"
              disabled={createPayment.isPending}
              className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
            >
              {createPayment.isPending ? 'Recording…' : 'Record payment'}
            </button>
          </form>
          {paymentError && (
            <div className="mt-3">
              <ErrorBanner message={paymentError} />
            </div>
          )}
        </div>
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
            Reschedule
          </Link>
          {!confirmingCancel ? (
            <button
              type="button"
              onClick={() => setConfirmingCancel(true)}
              className="rounded-lg border border-danger/40 px-4 py-2 text-sm font-medium text-danger hover:bg-danger-light"
            >
              Cancel appointment
            </button>
          ) : (
            <div className="flex items-center gap-3 rounded-lg border border-danger/30 bg-danger-light p-3">
              <p className="text-sm text-danger">Cancel this appointment?</p>
              <button
                type="button"
                disabled={cancelAppointment.isPending}
                onClick={handleCancel}
                className="shrink-0 rounded-lg bg-danger px-3 py-1.5 text-sm font-semibold text-white hover:bg-danger/90 disabled:opacity-50"
              >
                {cancelAppointment.isPending ? 'Cancelling…' : 'Yes, cancel'}
              </button>
              <button
                type="button"
                onClick={() => setConfirmingCancel(false)}
                className="shrink-0 text-sm text-ink-muted hover:underline"
              >
                Never mind
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
