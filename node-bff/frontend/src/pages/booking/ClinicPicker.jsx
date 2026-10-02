import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClinicsDirectory } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Card from '../../components/Card.jsx';
import { SearchIcon } from '../../components/icons.jsx';
import { matchesQuery } from '../../lib/tableUtils.js';

/** Step 1 of the booking flow - public, reachable logged-out. See ClinicController.clinics. */
export default function ClinicPicker() {
  const { t } = useTranslation();
  const { data, isLoading, isError, error, refetch } = useClinicsDirectory();
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    if (!data) return [];
    return data.filter((clinic) => matchesQuery(clinic, query, [(c) => c.name]));
  }, [data, query]);

  return (
    <PageContainer width="xl">
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
        <div className="flex flex-col gap-4">
          <div className="relative w-full max-w-sm">
            <SearchIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-muted" />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t('common.search')}
              className="w-full rounded-lg border border-slate-300 bg-surface py-2 pl-9 pr-3 text-sm text-ink focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20"
            />
          </div>

          {filtered.length === 0 ? (
            <EmptyState title={t('common.noResults')} description={t('common.noResultsHint')} />
          ) : (
            <div className="flex flex-col gap-2">
              {filtered.map((clinic) => (
                <Card key={clinic.id} as={Link} to={`/book/${clinic.id}`} hover>
                  <p className="font-medium text-ink">{clinic.name}</p>
                </Card>
              ))}
            </div>
          )}
        </div>
      )}
    </PageContainer>
  );
}
