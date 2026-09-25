import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { usePatients, useCreatePatient } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import EmptyState from '../../components/EmptyState.jsx';

const inputClass =
  'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/** Front-desk's entry point before booking a walk-in - search an existing patient, or register a new one. */
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

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-1 text-2xl font-bold text-ink">{t('nav.frontDesk.bookWalkIn')}</h1>
      <p className="mb-6 text-sm text-ink-muted">{t('patientSearch.subtitle')}</p>

      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={t('patientSearch.searchPlaceholder')}
        className={`${inputClass} mb-4`}
      />

      {isLoading && (
        <div className="flex flex-col gap-2">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      )}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {data && data.length === 0 && !showRegister && (
        <EmptyState
          title={t('patientSearch.noMatchTitle')}
          description={t('patientSearch.noMatchDescription')}
          action={
            <button
              type="button"
              onClick={() => setShowRegister(true)}
              className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-dark"
            >
              {t('patientSearch.registerNew')}
            </button>
          }
        />
      )}
      {data && data.length > 0 && (
        <div className="mb-4 flex flex-col gap-2">
          {data.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => navigate(`/front-desk/book/${p.id}`)}
              className="flex items-center justify-between rounded-xl border border-slate-200 bg-surface p-4 text-left shadow-sm transition-shadow hover:shadow-md"
            >
              <div>
                <p className="font-medium text-ink">
                  {p.firstName} {p.lastName}
                </p>
                {p.phone && <p className="text-xs text-ink-muted">{p.phone}</p>}
              </div>
              <span className="text-ink-muted">&rsaquo;</span>
            </button>
          ))}
        </div>
      )}

      {data && data.length > 0 && !showRegister && (
        <button
          type="button"
          onClick={() => setShowRegister(true)}
          className="text-sm font-medium text-brand-text hover:underline"
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
          <button
            type="submit"
            disabled={createPatient.isPending}
            className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50"
          >
            {createPatient.isPending ? t('patientSearch.registering') : t('patientSearch.registerAndContinue')}
          </button>
          {registerError && <ErrorBanner message={registerError} />}
        </form>
      )}
    </div>
  );
}
