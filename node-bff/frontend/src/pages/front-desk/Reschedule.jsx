import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAppointment, useProviders, useRescheduleAppointment } from '../../api/queries.js';
import { ApiError } from '../../api/client.js';
import SlotPicker from '../../components/booking/SlotPicker.jsx';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import Button from '../../components/Button.jsx';
import PageContainer from '../../components/PageContainer.jsx';

const selectClass =
  'w-full max-w-xs rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** Staff reschedule - same SlotPicker-reuse shape as the patient-facing Reschedule.jsx, posting to the staff endpoint instead. */
export default function Reschedule() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();

  const { data: appointment, isLoading, isError, error, refetch } = useAppointment(id);
  const providersQuery = useProviders(true, 'active');

  const [providerId, setProviderId] = useState('');
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [rescheduleError, setRescheduleError] = useState(null);
  const reschedule = useRescheduleAppointment(id);

  const effectiveProviderId = providerId || appointment?.providerId || '';

  async function handleConfirm() {
    setRescheduleError(null);
    try {
      await reschedule.mutateAsync({ newSlotId: selectedSlot.id, newProviderId: effectiveProviderId });
      navigate(`/front-desk/appointments/${id}`);
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
    <PageContainer width="xl">
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
          <option value="">{t('booking.select')}</option>
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
          <Button
            type="button"
            variant="accent"
            disabled={reschedule.isPending}
            onClick={handleConfirm}
            className="px-6 py-2.5"
          >
            {reschedule.isPending ? t('reschedule.rescheduling') : t('reschedule.confirmNewTime')}
          </Button>
        </div>
      )}

      {rescheduleError && (
        <div className="mt-4">
          <ErrorBanner message={rescheduleError} />
        </div>
      )}
    </PageContainer>
  );
}
