import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useReferrals, useCreateReferral, useUpdateReferral, usePatients, useProviders } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import { formatDateTime } from '../../lib/format.js';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

const STATUS_STYLE = {
  pending: 'bg-slate-100 text-ink-muted',
  accepted: 'bg-brand-light text-brand',
  scheduled: 'bg-warning-light text-warning',
  completed: 'bg-success-light text-success',
  declined: 'bg-danger-light text-danger',
};
const STATUSES = ['pending', 'accepted', 'scheduled', 'completed', 'declined'];
const PRIORITIES = ['routine', 'urgent'];

function Badge({ style, children }) {
  return <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold capitalize ${style}`}>{children}</span>;
}

function emptyForm() {
  return {
    patientId: '', referringProviderId: '', kind: 'internal', receivingProviderId: '',
    externalProviderName: '', externalClinicName: '', referredToSpecialty: '', reason: '', clinicalSummary: '', priority: 'routine',
  };
}

/**
 * Staff referral list + create form - GET/POST /api/referrals
 * (ReferralController), shared by provider+clinic_admin (identical
 * backend permissions on every endpoint, one route tree instead of
 * duplicating it per role - same reasoning as /lab-orders). Kept as one
 * page with an inline expand-to-update row rather than a separate detail
 * route (frontend phase L) - referrals have no multi-step status machine
 * the way lab orders do, just a single partial-update endpoint, so a
 * second route would be pure ceremony.
 */
export default function Referrals() {
  const { t } = useTranslation();
  const { data: referrals, isLoading, isError, error, refetch } = useReferrals(true);
  const { data: patients } = usePatients();
  const { data: providers } = useProviders(true, 'active');
  const createReferral = useCreateReferral();

  const patientById = useMemo(() => Object.fromEntries((patients || []).map((p) => [p.id, p])), [patients]);
  const providerById = useMemo(() => Object.fromEntries((providers || []).map((p) => [p.id, p])), [providers]);

  const [showCreate, setShowCreate] = useState(false);
  const [form, setForm] = useState(emptyForm());
  const [formError, setFormError] = useState(null);
  const [expandedId, setExpandedId] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    if (!form.patientId || !form.referringProviderId || !form.reason.trim()) {
      setFormError(t('referralsPage.errorRequired'));
      return;
    }
    if (form.kind === 'internal' && !form.receivingProviderId) {
      setFormError(t('referralsPage.errorReceivingProvider'));
      return;
    }
    if (form.kind === 'external' && !form.externalProviderName.trim() && !form.externalClinicName.trim()) {
      setFormError(t('referralsPage.errorExternalInfo'));
      return;
    }
    try {
      await createReferral.mutateAsync({
        patientId: form.patientId,
        referringProviderId: form.referringProviderId,
        receivingProviderId: form.kind === 'internal' ? form.receivingProviderId : undefined,
        externalProviderName: form.kind === 'external' ? form.externalProviderName.trim() || undefined : undefined,
        externalClinicName: form.kind === 'external' ? form.externalClinicName.trim() || undefined : undefined,
        referredToSpecialty: form.referredToSpecialty.trim() || undefined,
        reason: form.reason.trim(),
        clinicalSummary: form.clinicalSummary.trim() || undefined,
        priority: form.priority,
      });
      setForm(emptyForm());
      setShowCreate(false);
    } catch (err) {
      setFormError(err.message || t('referralsPage.errorCreate'));
    }
  }

  const sorted = [...(referrals || [])].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

  return (
    <div>
      <h1 className="mb-6 text-2xl font-bold text-ink">{t('nav.provider.referrals')}</h1>

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand hover:bg-brand-light/40"
        >
          {showCreate ? t('labOrdersPage.close') : t('referralsPage.newReferral')}
        </button>
      </div>

      {showCreate && (
        <>
          <form onSubmit={handleCreate} className="mb-4 rounded-xl border border-slate-200 bg-surface p-4">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <Field label={t('labOrdersPage.patient')}>
                <select value={form.patientId} onChange={(e) => setForm({ ...form, patientId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(patients || []).map((p) => <option key={p.id} value={p.id}>{p.firstName} {p.lastName}</option>)}
                </select>
              </Field>
              <Field label={t('referralsPage.referringProvider')}>
                <select value={form.referringProviderId} onChange={(e) => setForm({ ...form, referringProviderId: e.target.value })} className={`${inputClass} w-full`}>
                  <option value="">{t('booking.select')}</option>
                  {(providers || []).map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
                </select>
              </Field>
              <Field label={t('labOrdersPage.priority')}>
                <select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })} className={`${inputClass} w-full`}>
                  {PRIORITIES.map((p) => <option key={p} value={p}>{t(`labOrdersPage.${p}`)}</option>)}
                </select>
              </Field>
            </div>

            <div className="mt-4 flex items-center gap-4 text-sm">
              <label className="flex items-center gap-2">
                <input type="radio" checked={form.kind === 'internal'} onChange={() => setForm({ ...form, kind: 'internal' })} />
                {t('referralsPage.internalLabel')}
              </label>
              <label className="flex items-center gap-2">
                <input type="radio" checked={form.kind === 'external'} onChange={() => setForm({ ...form, kind: 'external' })} />
                {t('referralsPage.externalLabel')}
              </label>
            </div>

            {form.kind === 'internal' ? (
              <div className="mt-3">
                <Field label={t('referralsPage.receivingProvider')}>
                  <select value={form.receivingProviderId} onChange={(e) => setForm({ ...form, receivingProviderId: e.target.value })} className={`${inputClass} w-full max-w-xs`}>
                    <option value="">{t('booking.select')}</option>
                    {(providers || []).filter((p) => p.id !== form.referringProviderId).map((p) => <option key={p.id} value={p.id}>{p.fullName}</option>)}
                  </select>
                </Field>
              </div>
            ) : (
              <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field label={t('referralsPage.externalProviderName')}>
                  <input value={form.externalProviderName} onChange={(e) => setForm({ ...form, externalProviderName: e.target.value })} className={`${inputClass} w-full`} />
                </Field>
                <Field label={t('referralsPage.externalClinicName')}>
                  <input value={form.externalClinicName} onChange={(e) => setForm({ ...form, externalClinicName: e.target.value })} className={`${inputClass} w-full`} />
                </Field>
              </div>
            )}

            <div className="mt-4">
              <Field label={t('referralsPage.specialtyOptional')}>
                <input value={form.referredToSpecialty} onChange={(e) => setForm({ ...form, referredToSpecialty: e.target.value })} placeholder="Cardiology" className={`${inputClass} w-full max-w-xs`} />
              </Field>
            </div>
            <div className="mt-4">
              <Field label={t('referralsPage.reason')}>
                <textarea rows={2} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} className={`${inputClass} w-full`} />
              </Field>
            </div>
            <div className="mt-4">
              <Field label={t('referralsPage.clinicalSummaryOptional')}>
                <textarea rows={3} value={form.clinicalSummary} onChange={(e) => setForm({ ...form, clinicalSummary: e.target.value })} className={`${inputClass} w-full`} />
              </Field>
            </div>

            <button type="submit" disabled={createReferral.isPending} className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {createReferral.isPending ? t('labOrdersPage.creating') : t('referralsPage.createReferral')}
            </button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
        </>
      )}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && sorted.length === 0 && (
        <EmptyState title={t('referralsPage.emptyTitle')} description={t('referralsPage.emptyDescription')} />
      )}

      {!isLoading && !isError && sorted.length > 0 && (
        <div className="flex flex-col gap-2">
          {sorted.map((r) => (
            <ReferralRow
              key={r.id}
              referral={r}
              patient={patientById[r.patientId]}
              referringProvider={providerById[r.referringProviderId]}
              receivingProvider={r.receivingProviderId ? providerById[r.receivingProviderId] : null}
              expanded={expandedId === r.id}
              onToggle={() => setExpandedId((id) => (id === r.id ? null : r.id))}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ReferralRow({ referral, patient, referringProvider, receivingProvider, expanded, onToggle }) {
  const { t } = useTranslation();
  const updateReferral = useUpdateReferral(referral.id);
  const [form, setForm] = useState({ status: referral.status, priority: referral.priority, notes: referral.notes || '', clinicalSummary: referral.clinicalSummary || '' });
  const [saveError, setSaveError] = useState(null);
  const [saved, setSaved] = useState(false);

  const destination = referral.receivingProviderId
    ? (receivingProvider ? receivingProvider.fullName : '…')
    : [referral.externalProviderName, referral.externalClinicName].filter(Boolean).join(' · ') || t('referralsPage.externalLabel');

  async function handleSave(event) {
    event.preventDefault();
    setSaveError(null);
    setSaved(false);
    try {
      await updateReferral.mutateAsync({
        status: form.status,
        priority: form.priority,
        notes: form.notes.trim() || undefined,
        clinicalSummary: form.clinicalSummary.trim() || undefined,
      });
      setSaved(true);
    } catch (err) {
      setSaveError(err.message || t('common.errorSaveChanges'));
    }
  }

  return (
    <div className="rounded-xl border border-slate-200 bg-surface p-4">
      <button type="button" onClick={onToggle} className="flex w-full flex-wrap items-center justify-between gap-3 text-left">
        <div>
          <div className="mb-1 flex items-center gap-2">
            <Badge style={STATUS_STYLE[referral.status] || STATUS_STYLE.pending}>{t(`referralStatus.${referral.status}`, { defaultValue: referral.status })}</Badge>
            {referral.priority === 'urgent' && <Badge style="bg-danger-light text-danger">{t('labOrdersPage.urgent')}</Badge>}
            {referral.receivingProviderId ? null : <span className="text-xs text-ink-muted">{t('referralsPage.externalLabel')}</span>}
          </div>
          <p className="text-sm font-semibold text-ink">{patient ? `${patient.firstName} ${patient.lastName}` : '…'}</p>
          <p className="text-xs text-ink-muted">
            {referringProvider ? referringProvider.fullName : '…'} &rarr; {destination}
            {referral.referredToSpecialty && ` · ${referral.referredToSpecialty}`}
          </p>
        </div>
        <span className="text-xs text-ink-muted">{formatDateTime(referral.createdAt)}</span>
      </button>

      {expanded && (
        <form onSubmit={handleSave} className="mt-4 flex flex-col gap-3 border-t border-slate-100 pt-4">
          <p className="text-sm text-ink"><span className="font-semibold">{t('referralsPage.reasonLabel')}</span> {referral.reason}</p>
          <div className="flex flex-wrap items-end gap-3">
            <Field label={t('referralsPage.status')}>
              <select value={form.status} onChange={(e) => { setForm({ ...form, status: e.target.value }); setSaved(false); }} className={inputClass}>
                {STATUSES.map((s) => <option key={s} value={s}>{t(`referralStatus.${s}`)}</option>)}
              </select>
            </Field>
            <Field label={t('labOrdersPage.priority')}>
              <select value={form.priority} onChange={(e) => { setForm({ ...form, priority: e.target.value }); setSaved(false); }} className={inputClass}>
                {PRIORITIES.map((p) => <option key={p} value={p}>{t(`labOrdersPage.${p}`)}</option>)}
              </select>
            </Field>
          </div>
          <Field label={t('referralsPage.clinicalSummary')}>
            <textarea rows={2} value={form.clinicalSummary} onChange={(e) => { setForm({ ...form, clinicalSummary: e.target.value }); setSaved(false); }} className={`${inputClass} w-full`} />
          </Field>
          <Field label={t('referralsPage.notes')}>
            <textarea rows={2} value={form.notes} onChange={(e) => { setForm({ ...form, notes: e.target.value }); setSaved(false); }} className={`${inputClass} w-full`} />
          </Field>
          {referral.completedAt && <p className="text-xs text-ink-muted">{t('referralsPage.completedOn', { date: formatDateTime(referral.completedAt) })}</p>}
          {saveError && <ErrorBanner message={saveError} />}
          <div className="flex items-center gap-3">
            <button type="submit" disabled={updateReferral.isPending} className="self-start rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:cursor-not-allowed disabled:opacity-50">
              {updateReferral.isPending ? t('settingsPage.saving') : t('common.save')}
            </button>
            {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
          </div>
        </form>
      )}
    </div>
  );
}

function Field({ label, children }) {
  return (
    <label className="block text-left">
      <span className="mb-1 block text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</span>
      {children}
    </label>
  );
}
