import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyAppointment, useClinicProviders, useClinicsDirectory, useRescheduleMyAppointment } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import SlotPicker from '../components/booking/SlotPicker.jsx';
import Skeleton from '../components/Skeleton.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import { useActiveClinicZone } from '../theme/TimezoneProvider.jsx';

const selectClass =
  'w-full max-w-xs rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Patient self-reschedule - RescheduleService only allows moving to a new
 * slot of the *same* appointmentTypeId (a type change 400s server-side),
 * so that part is fixed here; providerId is re-pickable (still within
 * this same clinic).
 */
export default function Reschedule() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();

  const { data: appointment, isLoading, isError, error, refetch } = useMyAppointment(id);
  const providersQuery = useClinicProviders(appointment?.tenantId);
  const clinicsQuery = useClinicsDirectory();
  useActiveClinicZone(clinicsQuery.data?.find((c) => c.id === appointment?.tenantId)?.timezone);

  const [providerId, setProviderId] = useState('');
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [rescheduleError, setRescheduleError] = useState(null);
  const reschedule = useRescheduleMyAppointment(id);

  const effectiveProviderId = providerId || appointment?.providerId || '';

  async function handleConfirm() {
    setRescheduleError(null);
    try {
      await reschedule.mutateAsync({ newSlotId: selectedSlot.id, newProviderId: effectiveProviderId });
      navigate(`/appointments/${id}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setRescheduleError(err.message || t('reschedule.slotTaken'));
        setSelectedSlot(null);
        return;
      }
      setRescheduleError(err.message || t('reschedule.genericError'));
    }
  }

  if (isLoading) {
    return <Skeleton className="h-48 w-full max-w-xl" />;
  }
  if (isError) {
    return <ErrorBanner message={error?.message} onRetry={refetch} />;
  }

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('reschedule.title')}</h1>
      <p className="mb-6 text-sm text-ink-muted">
        {t('reschedule.refLabel')} <span className="font-mono">{appointment.appointmentRef}</span> - {t('reschedule.pickNewTime')}
      </p>

      <label className="mb-4 block">
        <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('reschedule.provider')}</span>
        <select
          value={effectiveProviderId}
          onChange={(e) => {
            setProviderId(e.target.value);
            setSelectedSlot(null);
          }}
          className={selectClass}
        >
          {providersQuery.data?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.fullName}
            </option>
          ))}
        </select>
      </label>

      <SlotPicker
        clinicId={appointment.tenantId}
        providerId={effectiveProviderId}
        appointmentTypeId={appointment.appointmentTypeId}
        selectedSlotId={selectedSlot?.id}
        onSelect={(slot) => {
          setSelectedSlot(slot);
          setRescheduleError(null);
        }}
      />

      {selectedSlot && (
        <div className="mt-6">
          <button
            type="button"
            disabled={reschedule.isPending}
            onClick={handleConfirm}
            className="rounded-lg bg-accent px-6 py-2.5 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {reschedule.isPending ? t('reschedule.rescheduling') : t('reschedule.confirmNewTime')}
          </button>
        </div>
      )}

      {rescheduleError && (
        <div className="mt-4">
          <ErrorBanner message={rescheduleError} />
        </div>
      )}
    </div>
  );
}
