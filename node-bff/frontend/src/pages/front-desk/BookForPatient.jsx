import { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { usePatient, useAppointmentTypes, useProviders, useCreateAppointment, useMyClinic } from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import SlotPicker from '../../components/booking/SlotPicker.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency } from '../../lib/format.js';

const selectClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * No clinic-picker step - front-desk's own clinic is already resolved from
 * their JWT (TenantContext), so this reads the staff appointment-type/
 * provider lists (not the public per-clinic directories the patient/guest
 * flow uses) and reuses the same SlotPicker/confirm shape as
 * pages/booking/BookingForm.jsx.
 */
export default function BookForPatient() {
  const { patientId } = useParams();
  const navigate = useNavigate();

  const { data: clinic } = useMyClinic(true);
  const { data: patient } = usePatient(patientId);
  const typesQuery = useAppointmentTypes(true, 'active');
  const providersQuery = useProviders(true, 'active');

  const [appointmentTypeId, setAppointmentTypeId] = useState('');
  const [providerId, setProviderId] = useState('');
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [bookingError, setBookingError] = useState(null);
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const createAppointment = useCreateAppointment();
  const clinicId = clinic?.id;

  async function handleConfirm() {
    setBookingError(null);
    try {
      const created = await createAppointment.mutateAsync({
        slotId: selectedSlot.id,
        providerId,
        appointmentTypeId,
        patientId,
        idempotencyKey: idempotencyKeyRef.current,
      });
      navigate(`/front-desk/appointments/${created.id}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setBookingError('That slot was just taken by someone else. Pick another.');
        setSelectedSlot(null);
        idempotencyKeyRef.current = crypto.randomUUID();
        return;
      }
      setBookingError(err.message || 'Could not book this appointment. Please try again.');
    }
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-1 text-2xl font-bold text-ink">
        Book for {patient ? `${patient.firstName} ${patient.lastName}` : '…'}
      </h1>
      <p className="mb-6 text-sm text-ink-muted">Pick an appointment type and a provider to see open times.</p>

      <div className="grid gap-4 sm:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Appointment type</span>
          {typesQuery.isLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : (
            <select
              value={appointmentTypeId}
              onChange={(e) => {
                setAppointmentTypeId(e.target.value);
                setSelectedSlot(null);
              }}
              className={selectClass}
            >
              <option value="">Select…</option>
              {typesQuery.data?.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name} ({t.durationMinutes} min, {formatCurrency(t.priceAmount)})
                </option>
              ))}
            </select>
          )}
        </label>

        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">Provider</span>
          {providersQuery.isLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : (
            <select
              value={providerId}
              onChange={(e) => {
                setProviderId(e.target.value);
                setSelectedSlot(null);
              }}
              className={selectClass}
            >
              <option value="">Select…</option>
              {providersQuery.data?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.fullName}
                </option>
              ))}
            </select>
          )}
        </label>
      </div>

      {appointmentTypeId && providerId && (
        <div className="mt-6">
          <h2 className="mb-3 text-sm font-semibold text-ink">Available times</h2>
          <SlotPicker
            clinicId={clinicId}
            providerId={providerId}
            appointmentTypeId={appointmentTypeId}
            selectedSlotId={selectedSlot?.id}
            onSelect={(slot) => {
              setSelectedSlot(slot);
              setBookingError(null);
            }}
          />
        </div>
      )}

      {selectedSlot && (
        <div className="mt-6">
          <button
            type="button"
            disabled={createAppointment.isPending}
            onClick={handleConfirm}
            className="w-full rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50 sm:w-auto"
          >
            {createAppointment.isPending ? 'Booking…' : 'Confirm booking'}
          </button>
        </div>
      )}

      {bookingError && (
        <div className="mt-4">
          <ErrorBanner message={bookingError} />
        </div>
      )}
    </div>
  );
}
