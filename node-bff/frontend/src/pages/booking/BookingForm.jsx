import { useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useClinicsDirectory,
  useClinicAppointmentTypes,
  useClinicProviders,
  useCreateAppointment,
  useCreateGuestAppointment,
} from '../../api/queries.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { ApiError } from '../../api/client.js';
import SlotPicker from '../../components/booking/SlotPicker.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatCurrency } from '../../lib/format.js';
import { useActiveClinicZone } from '../../theme/TimezoneProvider.jsx';
import PageContainer from '../../components/PageContainer.jsx';

const selectClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';
const inputClass = selectClass;

/** Step 2+3+4 of the booking flow, all on one page - pick a type/provider, pick a slot, confirm. */
export default function BookingForm() {
  const { t } = useTranslation();
  const { clinicId } = useParams();
  const navigate = useNavigate();
  const { authenticated } = useAuth();

  const { data: clinics } = useClinicsDirectory();
  const clinic = clinics?.find((c) => c.id === clinicId);
  const clinicName = clinic?.name;
  // Registers this clinic's own timezone so the slot picker below shows
  // times in it when the viewer's preference is "clinic" (see the
  // phase-18/phase-N reconciliation write-up in CLAUDE.md).
  useActiveClinicZone(clinic?.timezone);

  const typesQuery = useClinicAppointmentTypes(clinicId);
  const providersQuery = useClinicProviders(clinicId);

  const [appointmentTypeId, setAppointmentTypeId] = useState('');
  const [providerId, setProviderId] = useState('');
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [contactName, setContactName] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [bookingError, setBookingError] = useState(null);
  // Minted once per checkout attempt and reused across a retried click -
  // that's what lets the server's (tenant_id, idempotency_key) uniqueness
  // check actually protect against a double-booking on a flaky network.
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const createAppointment = useCreateAppointment();
  const createGuestAppointment = useCreateGuestAppointment();
  const booking = authenticated ? createAppointment : createGuestAppointment;

  const selectedType = useMemo(
    () => typesQuery.data?.find((type) => type.id === appointmentTypeId),
    [typesQuery.data, appointmentTypeId],
  );

  function handleTypeOrProviderChange(setter) {
    return (value) => {
      setter(value);
      setSelectedSlot(null);
      setBookingError(null);
    };
  }

  async function handleConfirm() {
    setBookingError(null);
    if (!authenticated && !contactName.trim()) {
      setBookingError(t('bookingForm.errorNameRequired'));
      return;
    }
    if (!authenticated && !contactPhone.trim()) {
      setBookingError(t('bookingForm.errorPhoneRequired'));
      return;
    }
    try {
      const created = await booking.mutateAsync(
        authenticated
          ? {
              slotId: selectedSlot.id,
              providerId,
              appointmentTypeId,
              idempotencyKey: idempotencyKeyRef.current,
            }
          : {
              slotId: selectedSlot.id,
              providerId,
              appointmentTypeId,
              contactName: contactName.trim(),
              contactPhone: contactPhone.trim(),
              contactEmail: contactEmail.trim() || undefined,
              idempotencyKey: idempotencyKeyRef.current,
            },
      );
      navigate(`/appointments/${created.id}`, {
        state: {
          appointment: created,
          clinicName,
          providerName: providersQuery.data?.find((p) => p.id === providerId)?.fullName,
          typeName: selectedType?.name,
          slot: selectedSlot,
        },
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setBookingError(t('booking.slotTaken'));
        setSelectedSlot(null);
        idempotencyKeyRef.current = crypto.randomUUID();
        return;
      }
      setBookingError(err.message || t('booking.errorBook'));
    }
  }

  return (
    <PageContainer>
      <h1 className="mb-1 text-2xl font-bold text-ink">{clinicName || t('publicNav.bookAppointment')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('booking.pickTypeAndProvider')}</p>

      <div className="grid gap-4 sm:grid-cols-2">
        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('booking.appointmentType')}</span>
          {typesQuery.isLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : (
            <select
              value={appointmentTypeId}
              onChange={(e) => handleTypeOrProviderChange(setAppointmentTypeId)(e.target.value)}
              className={selectClass}
            >
              <option value="">{t('booking.select')}</option>
              {typesQuery.data?.map((type) => (
                <option key={type.id} value={type.id}>
                  {type.name} ({type.durationMinutes} min, {formatCurrency(type.priceAmount)})
                </option>
              ))}
            </select>
          )}
        </label>

        <label className="block">
          <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('reschedule.provider')}</span>
          {providersQuery.isLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : (
            <select
              value={providerId}
              onChange={(e) => handleTypeOrProviderChange(setProviderId)(e.target.value)}
              className={selectClass}
            >
              <option value="">{t('booking.select')}</option>
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
          <h2 className="mb-3 text-sm font-semibold text-ink">{t('booking.availableTimes')}</h2>
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
        <div className="mt-6 rounded-xl border border-slate-200 bg-surface p-4 shadow-sm">
          {!authenticated && (
            <div className="mb-4">
              <p className="mb-3 text-sm font-semibold text-ink">{t('bookingForm.yourContactInfo')}</p>
              <p className="mb-3 text-xs text-ink-muted">
                {t('bookingForm.withoutAccountNote')} <span className="font-mono">/track-appointment</span>.
              </p>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('common.name')}</span>
                  <input value={contactName} onChange={(e) => setContactName(e.target.value)} className={inputClass} />
                </label>
                <label className="block">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.phone')}</span>
                  <input value={contactPhone} onChange={(e) => setContactPhone(e.target.value)} className={inputClass} />
                </label>
                <label className="block sm:col-span-2">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.emailOptional')}</span>
                  <input
                    type="email"
                    value={contactEmail}
                    onChange={(e) => setContactEmail(e.target.value)}
                    className={inputClass}
                  />
                </label>
              </div>
            </div>
          )}

          <button
            type="button"
            disabled={booking.isPending}
            onClick={handleConfirm}
            className="w-full rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50 sm:w-auto"
          >
            {booking.isPending ? t('booking.booking') : t('booking.confirmBooking')}
          </button>
        </div>
      )}

      {/* Deliberately outside the {selectedSlot && ...} panel above - a 409
          handler clears selectedSlot (so the taken slot's button stops
          looking selected), which would otherwise unmount this message in
          the same instant it appears. */}
      {bookingError && (
        <div className="mt-4">
          <ErrorBanner message={bookingError} />
        </div>
      )}
    </PageContainer>
  );
}
