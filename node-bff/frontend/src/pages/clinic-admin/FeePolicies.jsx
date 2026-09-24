import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useFeePolicies, useCreateFeePolicy, useUpdateFeePolicy, useDeleteFeePolicy, useProviders } from '../../api/queries.js';
import Skeleton from '../../components/Skeleton.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorBanner from '../../components/ErrorBanner.jsx';

const inputClass =
  'rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/20';

/**
 * "Fee Policies" tab of the clinic-admin settings hub - GET/POST/POST
 * .../update/POST .../delete FeePolicyController. A specific provider's
 * tiers *replace* the clinic-wide default entirely (never merged, see
 * FeeCalculator) - grouped here by provider for the same reason the
 * reference project's RefundPolicies.jsx groups by route.
 */
export default function ClinicAdminFeePolicies() {
  const { t } = useTranslation();
  const { data: policies, isLoading, isError, error, refetch } = useFeePolicies();
  const { data: providers } = useProviders(true);
  const createPolicy = useCreateFeePolicy();

  const providerById = Object.fromEntries((providers || []).map((p) => [p.id, p]));

  const [providerId, setProviderId] = useState('');
  const [cutoffHours, setCutoffHours] = useState('24');
  const [feePercent, setFeePercent] = useState('0');
  const [formError, setFormError] = useState(null);

  async function handleCreate(event) {
    event.preventDefault();
    setFormError(null);
    try {
      await createPolicy.mutateAsync({
        providerId: providerId || undefined,
        cutoffHours: Number(cutoffHours),
        feePercent: Number(feePercent),
      });
      setCutoffHours('24');
      setFeePercent('0');
    } catch (err) {
      setFormError(err.message || t('feePoliciesPage.errorCreate'));
    }
  }

  const groups = new Map();
  (policies || []).forEach((p) => {
    const key = p.providerId || 'default';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(p);
  });
  // Clinic-wide default first, then providers by name.
  const orderedKeys = [...groups.keys()].sort((a, b) => {
    if (a === 'default') return -1;
    if (b === 'default') return 1;
    return (providerById[a]?.fullName || '').localeCompare(providerById[b]?.fullName || '');
  });

  return (
    <div>
      <p className="mb-6 text-sm text-ink-muted">{t('feePoliciesPage.intro')}</p>

      <form onSubmit={handleCreate} className="mb-6 flex flex-wrap items-end gap-3 rounded-xl border border-slate-200 bg-surface p-4">
        <Field label={t('feePoliciesPage.appliesTo')}>
          <select value={providerId} onChange={(e) => setProviderId(e.target.value)} className={`${inputClass} w-56`}>
            <option value="">{t('feePoliciesPage.clinicWideDefault')}</option>
            {(providers || []).map((p) => (
              <option key={p.id} value={p.id}>{p.fullName}</option>
            ))}
          </select>
        </Field>
        <Field label={t('feePoliciesPage.cutoffHours')}>
          <input type="number" min="0" value={cutoffHours} onChange={(e) => setCutoffHours(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('feePoliciesPage.feePercent')}>
          <input type="number" min="0" max="100" value={feePercent} onChange={(e) => setFeePercent(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <button type="submit" disabled={createPolicy.isPending} className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {createPolicy.isPending ? t('common.adding') : t('feePoliciesPage.addTier')}
        </button>
      </form>
      {formError && <div className="mb-4"><ErrorBanner message={formError} /></div>}

      {isLoading && <Skeleton className="h-32 w-full" />}
      {isError && <ErrorBanner message={error?.message} onRetry={refetch} />}
      {!isLoading && !isError && (policies || []).length === 0 && (
        <EmptyState title={t('feePoliciesPage.emptyTitle')} description={t('feePoliciesPage.emptyDescription')} />
      )}

      {!isLoading && !isError && orderedKeys.length > 0 && (
        <div className="flex flex-col gap-4">
          {orderedKeys.map((key) => (
            <div key={key} className="rounded-xl border border-slate-200 bg-surface p-4">
              <p className="mb-3 text-sm font-semibold text-ink">
                {key === 'default' ? t('feePoliciesPage.clinicWideDefault') : providerById[key]?.fullName || t('feePoliciesPage.unknownProvider')}
              </p>
              <div className="flex flex-col gap-2">
                {groups.get(key)
                  .sort((a, b) => b.cutoffHours - a.cutoffHours)
                  .map((tier) => (
                    <TierRow key={tier.id} tier={tier} />
                  ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TierRow({ tier }) {
  const { t } = useTranslation();
  const updatePolicy = useUpdateFeePolicy(tier.id);
  const deletePolicy = useDeleteFeePolicy(tier.id);

  const [editing, setEditing] = useState(false);
  const [cutoffHours, setCutoffHours] = useState(String(tier.cutoffHours));
  const [feePercent, setFeePercent] = useState(String(tier.feePercent));
  const [rowError, setRowError] = useState(null);

  async function saveEdit() {
    setRowError(null);
    try {
      await updatePolicy.mutateAsync({ cutoffHours: Number(cutoffHours), feePercent: Number(feePercent) });
      setEditing(false);
    } catch (err) {
      setRowError(err.message || t('common.errorSaveChanges'));
    }
  }

  async function handleDelete() {
    setRowError(null);
    try {
      await deletePolicy.mutateAsync();
    } catch (err) {
      setRowError(err.message || t('feePoliciesPage.errorDelete'));
    }
  }

  if (editing) {
    return (
      <div className="flex flex-wrap items-end gap-3">
        <Field label={t('feePoliciesPage.cutoffHours')}>
          <input type="number" min="0" value={cutoffHours} onChange={(e) => setCutoffHours(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <Field label={t('feePoliciesPage.feePercent')}>
          <input type="number" min="0" max="100" value={feePercent} onChange={(e) => setFeePercent(e.target.value)} className={`${inputClass} w-24`} />
        </Field>
        <button type="button" onClick={saveEdit} disabled={updatePolicy.isPending} className="rounded-lg bg-accent px-3 py-2 text-sm font-semibold text-white hover:bg-accent-dark disabled:opacity-50">
          {t('common.save')}
        </button>
        <button type="button" onClick={() => setEditing(false)} className="text-sm text-ink-muted hover:underline">
          {t('common.cancel')}
        </button>
        {rowError && <div className="w-full"><ErrorBanner message={rowError} /></div>}
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between">
      <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-ink">
        {t('feePoliciesPage.tierLabel', { hours: tier.cutoffHours, percent: tier.feePercent })}
      </span>
      <div className="flex items-center gap-3 text-sm">
        <button type="button" onClick={() => setEditing(true)} className="text-brand hover:underline">{t('common.edit')}</button>
        <button type="button" onClick={handleDelete} className="text-danger hover:underline">{t('common.delete')}</button>
      </div>
      {rowError && <div className="mt-2"><ErrorBanner message={rowError} /></div>}
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
