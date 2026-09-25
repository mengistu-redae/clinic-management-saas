import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useAvailability } from '../../api/queries.js';
import Skeleton from '../Skeleton.jsx';
import ErrorBanner from '../ErrorBanner.jsx';
import EmptyState from '../EmptyState.jsx';
import { formatDayLabel, formatTime } from '../../lib/format.js';

/**
 * Open slots for one clinic+provider+appointment-type combo, grouped by
 * day - shared by the booking flow (pages/booking/BookingForm.jsx) and
 * self-reschedule (pages/Reschedule.jsx). Selection is fully controlled
 * (selectedSlotId/onSelect) so each caller decides what happens next -
 * BookingForm reveals a confirm panel, Reschedule submits directly.
 */
export default function SlotPicker({ clinicId, providerId, appointmentTypeId, selectedSlotId, onSelect }) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useAvailability(clinicId, providerId, appointmentTypeId);

  const groups = useMemo(() => {
    if (!data) return [];
    const byDay = new Map();
    for (const slot of data) {
      const label = formatDayLabel(slot.startTime);
      if (!byDay.has(label)) byDay.set(label, []);
      byDay.get(label).push(slot);
    }
    return Array.from(byDay.entries());
  }, [data]);

  if (!providerId || !appointmentTypeId) {
    return null;
  }
  if (isLoading) {
    return <Skeleton className="h-40 w-full" />;
  }
  if (isError) {
    return <ErrorBanner message={error?.message} onRetry={refetch} />;
  }
  if (groups.length === 0) {
    return <EmptyState title={t('slotPicker.noSlotsTitle')} description={t('slotPicker.noSlotsDescription')} />;
  }

  return (
    <div className="flex flex-col gap-4">
      {groups.map(([day, slots]) => (
        <div key={day}>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-muted">{day}</p>
          <div className="flex flex-wrap gap-2">
            {slots.map((slot) => (
              <button
                key={slot.id}
                type="button"
                onClick={() => onSelect(slot)}
                className={`rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors ${
                  selectedSlotId === slot.id
                    ? 'border-brand bg-brand text-white'
                    : 'border-slate-300 text-ink hover:border-brand hover:text-brand-text'
                }`}
              >
                {formatTime(slot.startTime)}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
