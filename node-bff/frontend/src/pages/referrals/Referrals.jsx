import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useReferrals, useCreateReferral, useUpdateReferral, usePatients, useProviders } from '../../api/queries.js';
import ErrorBanner from '../../components/ErrorBanner.jsx';
import DataTable from '../../components/DataTable.jsx';
import PageContainer from '../../components/PageContainer.jsx';
import Button from '../../components/Button.jsx';
import Field, { inputClass } from '../../components/Field.jsx';
import { formatDateTime } from '../../lib/format.js';

const STATUS_STYLE = {
  pending: 'bg-slate-100 text-ink-muted',
  accepted: 'bg-brand-light text-brand-text',
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
 * second route would be pure ceremony. Now rendered via DataTable
 * (modern-UI redesign) - its own chevron toggle replaces this page's old
 * bespoke expand button, same update form underneath.
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
  const [statusFilter, setStatusFilter] = useState('');

  function patientName(r) {
    const p = patientById[r.patientId];
    return p ? `${p.firstName} ${p.lastName}` : '';
  }
  function destinationName(r) {
    if (r.receivingProviderId) return providerById[r.receivingProviderId]?.fullName || '';
    return [r.externalProviderName, r.externalClinicName].filter(Boolean).join(' · ');
  }

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

  const columns = [
    { key: 'patient', header: t('labOrdersPage.patient'), accessor: patientName, sortable: true },
    {
      key: 'destination',
      header: t('referralsPage.receivingProvider'),
      accessor: destinationName,
      sortable: true,
      render: (r) => (
        <span>
          {providerById[r.referringProviderId]?.fullName || '…'} &rarr; {destinationName(r) || t('referralsPage.externalLabel')}
        </span>
      ),
    },
    { key: 'specialty', header: t('referralsPage.specialtyOptional'), accessor: (r) => r.referredToSpecialty || '', sortable: true, render: (r) => r.referredToSpecialty || '—' },
    {
      key: 'status',
      header: t('referralsPage.status'),
      accessor: (r) => r.status,
      sortable: true,
      render: (r) => (
        <div className="flex items-center gap-2">
          <Badge style={STATUS_STYLE[r.status] || STATUS_STYLE.pending}>{t(`referralStatus.${r.status}`, { defaultValue: r.status })}</Badge>
          {r.priority === 'urgent' && <Badge style="bg-danger-light text-danger">{t('labOrdersPage.urgent')}</Badge>}
        </div>
      ),
    },
    { key: 'createdAt', header: t('common.bookedAt'), accessor: (r) => r.createdAt, sortAccessor: (r) => new Date(r.createdAt), sortable: true, render: (r) => formatDateTime(r.createdAt) },
  ];

  const visibleReferrals = (referrals || []).filter((r) => !statusFilter || r.status === statusFilter);

  return (
    <PageContainer width="lg">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-ink">{t('nav.provider.referrals')}</h1>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          aria-label={t('common.filterByStatus')}
          className={`${inputClass} w-44`}
        >
          <option value="">{t('common.all')}</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>{t(`referralStatus.${s}`)}</option>
          ))}
        </select>
      </div>

      <div className="mb-6">
        <button
          type="button"
          onClick={() => setShowCreate((v) => !v)}
          className="rounded-lg border border-brand/40 px-3 py-1.5 text-sm font-medium text-brand-text hover:bg-brand-light/40"
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
                <input type="radio" checked={form.kind === 'internal'} onChange={() => setForm({ ...form, kind: 'internal' })} className="h-4 w-4 accent-brand" />
                {t('referralsPage.internalLabel')}
              </label>
              <label className="flex items-center gap-2">
                <input type="radio" checked={form.kind === 'external'} onChange={() => setForm({ ...form, kind: 'external' })} className="h-4 w-4 accent-brand" />
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

            <Button type="submit" variant="accent" className="mt-4" disabled={createReferral.isPending}>
              {createReferral.isPending ? t('labOrdersPage.creating') : t('referralsPage.createReferral')}
            </Button>
          </form>
          {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}
        </>
      )}

      <DataTable
        columns={columns}
        rows={visibleReferrals}
        rowKey="id"
        searchAccessors={[patientName, destinationName, (r) => r.referredToSpecialty]}
        renderExpanded={(r) => <ReferralEditPanel referral={r} />}
        defaultSortKey="createdAt"
        defaultSortDir="desc"
        isLoading={isLoading}
        error={isError ? error : null}
        onRetry={refetch}
        emptyTitle={t('referralsPage.emptyTitle')}
        emptyDescription={t('referralsPage.emptyDescription')}
      />
    </PageContainer>
  );
}

function ReferralEditPanel({ referral }) {
  const { t } = useTranslation();
  const updateReferral = useUpdateReferral(referral.id);
  const [form, setForm] = useState({ status: referral.status, priority: referral.priority, notes: referral.notes || '', clinicalSummary: referral.clinicalSummary || '' });
  const [saveError, setSaveError] = useState(null);
  const [saved, setSaved] = useState(false);

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
    <form onSubmit={handleSave} className="flex flex-col gap-3">
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
        <Button type="submit" variant="accent" className="self-start" disabled={updateReferral.isPending}>
          {updateReferral.isPending ? t('settingsPage.saving') : t('common.save')}
        </Button>
        {saved && <span className="text-sm text-success">{t('settingsPage.saved')}</span>}
      </div>
    </form>
  );
}
