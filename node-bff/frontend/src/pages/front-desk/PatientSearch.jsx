import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { usePatients, useCreatePatient } from '../../api/queries.js';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import { SearchIcon } from '../../components/icons.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * Front-desk's entry point before booking a walk-in - search an existing
 * patient, or register a new one. Its own free-text box is real
 * server-side search (GET /api/patients?query=, the one list-fetching
 * hook in this app with a real search param) - kept as the single source
 * of truth rather than duplicated as a second client-side search box the
 * way DataTable would otherwise add; results still render through
 * DataTable for sortable columns + the row-click-to-book behavior.
 */
export default function PatientSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [showRegister, setShowRegister] = useState(false);
  const [form, setForm] = useState({ firstName: '', lastName: '', phone: '', dateOfBirth: '', email: '', nationalId: '' });
  const [registerError, setRegisterError] = useState(null);

  const { data, isLoading, isError, error, refetch } = usePatients(query);
  const createPatient = useCreatePatient();

  async function handleRegister(event) {
    event.preventDefault();
    setRegisterError(null);
    if (!form.firstName.trim() || !form.lastName.trim()) {
      setRegisterError(t('patientSearch.errorNameRequired'));
      return;
    }
    try {
      const patient = await createPatient.mutateAsync({
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        phone: form.phone.trim() || undefined,
        dateOfBirth: form.dateOfBirth || undefined,
        email: form.email.trim() || undefined,
        nationalId: form.nationalId.trim() || undefined,
      });
      navigate(`/front-desk/book/${patient.id}`);
    } catch (err) {
      setRegisterError(err.message || t('patientSearch.errorRegister'));
    }
  }

  const columns = [
    { key: 'name', header: t('common.name'), accessor: (p) => `${p.firstName} ${p.lastName}`, sortable: true },
    { key: 'phone', header: t('patientSearch.phone'), accessor: (p) => p.phone || '', sortable: true, render: (p) => p.phone || '—' },
    { key: 'dateOfBirth', header: t('patientSearch.dateOfBirth'), accessor: (p) => p.dateOfBirth || '', sortable: true, render: (p) => p.dateOfBirth || '—' },
  ];

  return (
    <PageContainer width="lg">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('nav.frontDesk.bookWalkIn')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('patientSearch.subtitle')}</p>

      <div className="relative mb-4 max-w-sm">
        <label htmlFor="patient-search-query" className="sr-only">
          {t('patientSearch.searchPlaceholder')}
        </label>
        <SearchIcon className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-muted" />
        <input
          id="patient-search-query"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('patientSearch.searchPlaceholder')}
          className={`${inputClass} pl-9`}
        />
      </div>

      {data && data.length === 0 && !showRegister && (
        <EmptyState
          title={t('patientSearch.noMatchTitle')}
          description={t('patientSearch.noMatchDescription')}
          action={
            <Button type="button" onClick={() => setShowRegister(true)}>
              {t('patientSearch.registerNew')}
            </Button>
          }
        />
      )}

      {(isLoading || isError || (data && data.length > 0)) && (
        <DataTable
          columns={columns}
          rows={data || []}
          rowKey="id"
          onRowClick={(p) => navigate(`/front-desk/book/${p.id}`)}
          defaultSortKey="name"
          isLoading={isLoading}
          error={isError ? error : null}
          onRetry={refetch}
        />
      )}

      {data && data.length > 0 && !showRegister && (
        <button
          type="button"
          onClick={() => setShowRegister(true)}
          className="mt-4 text-sm font-medium text-brand-text hover:underline"
        >
          {t('patientSearch.notListed')}
        </button>
      )}

      {showRegister && (
        <form onSubmit={handleRegister} className="mt-4 flex flex-col gap-3 rounded-xl border border-slate-200 bg-surface p-4">
          <p className="text-sm font-semibold text-ink">{t('patientSearch.registerNew')}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.firstName')}</span>
              <input value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} className={inputClass} />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.lastName')}</span>
              <input value={form.lastName} onChange={(e) => setForm({ ...form, lastName: e.target.value })} className={inputClass} />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.phone')}</span>
              <input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} className={inputClass} />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.dateOfBirth')}</span>
              <input
                type="date"
                value={form.dateOfBirth}
                onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })}
                className={inputClass}
              />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.nationalIdOptional')}</span>
              <input value={form.nationalId} onChange={(e) => setForm({ ...form, nationalId: e.target.value })} className={inputClass} />
            </label>
            <label className="block">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{t('patientSearch.emailOptional')}</span>
              <input
                type="email"
                value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })}
                className={inputClass}
              />
            </label>
          </div>
          <Button type="submit" variant="accent" className="self-start" disabled={createPatient.isPending}>
            {createPatient.isPending ? t('patientSearch.registering') : t('patientSearch.registerAndContinue')}
          </Button>
          {registerError && <ErrorBanner message={registerError} />}
        </form>
      )}
    </PageContainer>
  );
}
