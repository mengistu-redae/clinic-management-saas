import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClinicsDirectory } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';

/** Step 1 of the booking flow - public, reachable logged-out. See ClinicController.clinics. */
export default function ClinicPicker() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useClinicsDirectory();

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('publicNav.bookAppointment')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('clinicPicker.subtitle')}</p>

      {isLoading && (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      )}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {data && data.length === 0 && (
        <EmptyState title={t('clinicPicker.noneTitle')} description={t('clinicPicker.noneDescription')} />
      )}
      {data && data.length > 0 && (
        <div className="flex flex-col gap-2">
          {data.map((clinic) => (
            <Link
              key={clinic.id}
              to={`/book/${clinic.id}`}
              className="rounded-xl border border-slate-200 bg-surface p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <p className="font-medium text-ink">{clinic.name}</p>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
