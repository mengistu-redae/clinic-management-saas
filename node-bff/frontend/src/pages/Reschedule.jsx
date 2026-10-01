import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMyAppointment, useClinicProviders, useClinicsDirectory, useRescheduleMyAppointment } from '../api/queries.js';
import { ApiError } from '../api/client.js';
import SlotPicker from '../components/booking/SlotPicker.jsx';
import Skeleton from '../components/Skeleton.jsx';
import ErrorBanner from '../components/ErrorBanner.jsx';
import { useActiveClinicZone } from '../theme/TimezoneProvider.jsx';
import PageContainer from '../components/PageContainer.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Button from '../components/Button.jsx';
import Field, { inputClass } from '../components/Field.jsx';

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
    <PageContainer width="xl">
      <PageHeader
        title={t('reschedule.title')}
        description={
          <>
            {t('reschedule.refLabel')} <span className="font-mono">{appointment.appointmentRef}</span> - {t('reschedule.pickNewTime')}
          </>
        }
      />

      <Field label={t('reschedule.provider')}>
        <select
          value={effectiveProviderId}
          onChange={(e) => {
            setProviderId(e.target.value);
            setSelectedSlot(null);
          }}
          className={`${inputClass} w-full max-w-xs`}
        >
          {providersQuery.data?.map((p) => (
            <option key={p.id} value={p.id}>
              {p.fullName}
            </option>
          ))}
        </select>
      </Field>

      <div className="mt-4">
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
      </div>

      <div className="mt-6 flex items-center gap-3">
        {selectedSlot && (
          <Button variant="accent" disabled={reschedule.isPending} onClick={handleConfirm}>
            {reschedule.isPending ? t('reschedule.rescheduling') : t('reschedule.confirmNewTime')}
          </Button>
        )}
        {/* A patient who opens this page and changes their mind previously had no way out
            except the browser back button - every sibling flow in this app gives one. */}
        <Button as={Link} to={`/appointments/${id}`} variant="ghost">
          {t('common.cancel')}
        </Button>
      </div>

      {rescheduleError && (
        <div className="mt-4">
          <ErrorBanner message={rescheduleError} />
        </div>
      )}
    </PageContainer>
  );
}
